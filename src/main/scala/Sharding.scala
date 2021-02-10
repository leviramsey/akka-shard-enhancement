import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, RecipientRef }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityRef, EntityTypeKey }

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{ Success, Try }

object Sharding {
  /** Typeclass for encoding domain type to entity ID string and decoding entity ID string to
   *  domain type.
   *
   *  laws:
   *  decode(encode(domain)).get == domain
   *  decode(entityId).map(encode).get == entityId
   */
  trait EntityIdentifier[T] {
    def encode(t: T): String
    def decode(s: String): Try[T]
  }

  object EntityIdentifier {
    def apply[T: EntityIdentifier] = implicitly[EntityIdentifier[T]]

    implicit val ForString: EntityIdentifier[String] =
      new EntityIdentifier[String] {
        def encode(s: String): String = s
        def decode(s: String): Try[String] = Success(s)
      }
  }

  // Using ID so there's no possible collision with cats.Id
  sealed trait EntityMeta[Command, ID] {
    /** The name of this type of entity.  Must be unique in the application
     */
    def name: String

    /** If defined, the message that will be sent to passivate this entity after inactivity.
     *  Otherwise, sharding will simply stop the entity.
     */
    def stopMessage: Option[Command]

    def TypeKey: EntityTypeKey[Command]
    def EntityDef: Entity[Command, ShardingEnvelope[Command]]

    /** Obtain a reference for an entity accepting 'Command's with identity given by 'id'.
     *
     *  Result type is RecipientRef to facilitate use in tests without actual sharding
     */
    def entityRefFor(system: ActorSystem[Nothing], id: ID): RecipientRef[Command]

    /** Obtain a reference for an entity accepting 'Command's with identity given by 'stringId'.
     *  This mainly exists to support deserialization of EntityRefs.
     *
     *  Result type is RecipientRef to facilitate use in tests without actual sharding
     */
    def entityRefForStringId(system: ActorSystem[Nothing], stringId: String): RecipientRef[Command]
  }

  object EntityMeta {
    def apply[Command: ClassTag, ID: EntityIdentifier](
      name: String,
      f: ID => Behavior[Command]
    ): EntityMeta[Command, ID] =
      apply(name, f, None)

    def apply[Command: ClassTag, ID: EntityIdentifier](
      name: String,
      f: ID => Behavior[Command],
      stopMessage: Option[Command]
    ): EntityMeta[Command, ID] =
      new Impl(name, f, stopMessage)

    def apply[Command: ClassTag, ID: EntityIdentifier](
      name: String,
      f: (ActorRef[ClusterSharding.ShardCommand], ID) => Behavior[Command],
      stopMessage: Option[Command] = None
    ): EntityMeta[Command, ID] =
      new ImplWithShardActor(name, f, stopMessage)

    def test[Command, ID: EntityIdentifier](
      name: String,
      f: ID => ActorRef[Command]
    ): EntityMeta[Command, ID] =
      new TestImpl(name, f)

    /** EntityMeta for use when actually sharding (e.g. outside of unit tests).
     *
     *  Will actually result in EntityRefs
     */
    trait RealEntityMeta[Command, ID] extends EntityMeta[Command, ID] {
      override def entityRefFor(system: ActorSystem[Nothing], id: ID): EntityRef[Command]

      def entityRefForStringId(system: ActorSystem[Nothing], stringId: String): EntityRef[Command] =
        ClusterSharding(system).entityRefFor(TypeKey, stringId)
    }

    class Impl[Command: ClassTag, ID: EntityIdentifier](
      override val name: String,
      f: ID => Behavior[Command],
      override val stopMessage: Option[Command]
    ) extends RealEntityMeta[Command, ID] {
      val TypeKey = EntityTypeKey(name)

      val EntityDef = {
        val baseEntity = Entity(TypeKey) { entityContext =>
          tryEntityBehavior(name, entityContext.shard, f)(EntityIdentifier[ID].decode(entityContext.entityId))
        }

        stopMessage.map(m => baseEntity.withStopMessage(m))
          .getOrElse(baseEntity)
      }

      def entityRefFor(system: ActorSystem[Nothing], id: ID): EntityRef[Command] =
        entityRefForStringId(system, EntityIdentifier[ID].encode(id))
    }

    class ImplWithShardActor[Command: ClassTag, ID: EntityIdentifier](
      override val name: String,
      f: (ActorRef[ClusterSharding.ShardCommand], ID) => Behavior[Command],
      override val stopMessage: Option[Command]
    ) extends RealEntityMeta[Command, ID] {
      val TypeKey = EntityTypeKey(name)
      val EntityDef = {
        val baseEntity = Entity(TypeKey) { entityContext =>
          val decoded = EntityIdentifier[ID].decode(entityContext.entityId)

          tryEntityBehavior(name, entityContext.shard, f(entityContext.shard, _))(decoded)
        }

        stopMessage.map(m => baseEntity.withStopMessage(m))
          .getOrElse(baseEntity)
      }

      def entityRefFor(system: ActorSystem[Nothing], id: ID): EntityRef[Command] =
        entityRefForStringId(system, EntityIdentifier[ID].encode(id))
    }

    class TestImpl[Command, ID: EntityIdentifier](
      override val name: String,
      f: ID => ActorRef[Command]
    ) extends EntityMeta[Command, ID] {
      def stopMessage: Option[Command] = None
      def TypeKey = ???
      def EntityDef = ???

      def entityRefFor(system: ActorSystem[Nothing], id: ID): ActorRef[Command] = f(id)

      def entityRefForStringId(system: ActorSystem[Nothing], stringId: String): ActorRef[Command] =
        EntityIdentifier[ID].decode(stringId).map(entityRefFor(system, _)).get
    }

    /** A sharded behavior which will defer to the wrapped behavior if the identifier is valid, or
     *  spew errors to the log if the identifier is invalid.
     */
    def tryEntityBehavior[C, I](
      entityName: String,
      shard: ActorRef[ClusterSharding.ShardCommand],
      wrapped: I => Behavior[C])(
      tid: Try[I]
    ): Behavior[C] =
      tid.fold(
        { t =>
          val rawMsg = t.getMessage     // exception's message
          UsefulBehaviors.errorLog(
            s"Message sent to cluster-sharded entity $entityName with bad ID ($rawMsg)",
            { context => shard ! ClusterSharding.Passivate(context.self) },
            s"Cluster-sharded entity $entityName spawned with bad ID ($rawMsg)"
          ).narrow[C]
        },
        wrapped
      )
  }

  /** Sharded behavior which adapts and forwards messages.
   *
   *  @param id the identity of the target (and likely of this) entity
   *  @param target function to retrieve the target
   *  @param adaptation the message adapter function
   */
  def adapterBehavior[OutsideCommand, InsideCommand, ID](
    id: ID)(
    target: (ActorSystem[Nothing], ID) => RecipientRef[InsideCommand])(
    adaptation: OutsideCommand => InsideCommand
  ): ActorRef[ClusterSharding.ShardCommand] => Behavior[OutsideCommand] = { shard =>
    Behaviors.setup { context =>
      val forwardRef = target(context.system, id)
      Behaviors.receiveMessage { msg =>
        forwardRef ! adaptation(msg)
        shard ! ClusterSharding.Passivate(context.self)
        Behaviors.same
      }
    }
  }
}

object UsefulBehaviors {
  /** A Behavior that spews error logs on every message sent (and optionally on creation).
   *
   *  It's primarily useful to denote invalid input when a total function with result type Behavior
   *  is desired, and a "stop immediately" behavior would result in excessive respawning.  An example
   *  would be cluster-sharded actors where only some strings are valid entity IDs.
   *
   *  "The Behavior That Should Not Be"
   */
  def errorLog(
    logOnMsg: String,
    afterIdle: ActorContext[Any] => Unit,
    logOnSpawn: String = ""
  ): Behavior[Any] =
    Behaviors.setup { context =>
      if (logOnSpawn.nonEmpty) {
        context.log.error(logOnSpawn)
      }

      val stopMsg = new AnyRef()
      context.setReceiveTimeout(1.minute, stopMsg)

      Behaviors.receiveMessage { msg =>
        msg match {
          case m: AnyRef if m eq stopMsg =>
            afterIdle(context)
            Behaviors.same
          case _ =>
            context.log.error(logOnMsg)
            Behaviors.same
        }
      }
    }
}
