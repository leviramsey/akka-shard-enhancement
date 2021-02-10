Enhancing the utility of `EntityRef`s in typed cluster sharding
===============================================================

Akka Typed Cluster Sharding (or is that Cluster Sharding Typed...?) introduces the `EntityRef`, which
abstracts over sending messages to cluster sharded entities and potentially allows protocols not
designed for sharding to become sharded.

Unfortunately, at this writing, `EntityRef` (and by extension `RecipientRef`, its common supertype
with `ActorRef`) suffer from some notable limitations:

* It is not presently possible to usefully serialize an `EntityRef` (PR #29960 against akka/akka lays
the groundwork for custom serialization schemes: a default Jackson scheme may be forthcoming).  This
prevents inclusion of `EntityRef` in any message which will travel over the network (this further
renders `EntityRef`s largely useless in persistent behaviors).

* Even if `EntityRef`s were more usable in messages, the absence of a method "out of the box" to have
a message adapter for an `EntityRef` would still make a sharded protocol much more tightly coupled
than a non-sharded protocol.  It should be noted that this tight coupling is present in sharded
protocols which don't (because they can't) take advantage of `EntityRef`:

```
final case class ShardedGetValueForTargetEntity(
  replyToEntityId: String,
  typeKey: EntityTypeKey[TargetEntity.ValueIs]
) extends Command

final case class UnshardedGetValue(replyTo: ActorRef[ValueIs]) extends Command

final case class ValueIs(...)
```

With serialization of `EntityRef`, we can at least have:

```
final case class ShardedGetValueForTargetEntity(
  replyTo: EntityRef[TargetEntity.ValueIs]
) extends Command
```

which at least allows the actor implementing this protocol to not have to know that it's
sending to an `EntityRef`, but we would still likely need to define a `ShardedGetValueFor...`
message for every entity type which might need to participate as a getter in this protocol.

* Any string is a valid entity ID and there is no way to use a domain type (including those which
can enforce invariants) as an entity ID.

This repo outlines a pattern for addressing the latter two limitations.  It is Scala-centric: a
translation to Java is likely to perhaps be too verbose to be interesting (at least too much so
to be of interest for me: a PR with a Java translation will at least get a review).

## An example

Imagine that we have a `ComponentID`, consisting of two parts `context` and `id`.  `context` could
be analogous to a tenant in a multi-tenant system: it serves to namespace `id`.

```
sealed abstract case class ComponentID private[ComponentID](context: String, id: String) {
  override def toString: String = s"$context:$id"

  def copy(context: String = context, id: String = id): Option[ComponentID] = ComponentID(context, id)
}

object ComponentID {
  val Regex = """^([^:]+):(^[:]+)$""".r

  def apply(context: String, id: String): Option[ComponentID] =
    if (id.isEmpty || context.isEmpty || id.contains(':') || context.contains(':')) None
    else Some(unsafeApply(context, id))

  def fromString(str: String): Option[ComponentID] =
    str match {
      case Regex(context, id) => Some(unsafeApply(context, id))
      case _ => None
    }

  private def unsafeApply(context: String, id: String): ComponentID =
    new ComponentID(context, id)
}
```

The `sealed abstract case class` with `private` constructor pattern here prevents (absent a Java
serialization path, which can be handled via `readResolve` if that's part of your model of possible
corruptions) an invalid `ComponentID` from being created.  Any string with exactly one colon, which colon
is neither the first nor last character of the string, is a valid string representation of a `ComponentID`.

To use this as an entity ID, we would first define an `EntityIdentifier[ComponentID]`:

```
import scala.util.{ Failure, Success, Try }

object ComponentEntity {
  implicit val entityIdentifier: Sharding.EntityIdentifier[ComponentID] =
    new EntityIdentifier[ComponentID] {
      def encode(c: ComponentID): String = c.toString
      def decode(s: String): Try[ComponentID] =
        ComponentID.fromString(s)
          .map { c => Success(c) }
          .getOrElse(Failure(new IllegalArgumentException(s"$s was not a valid component ID")))
    }

  sealed trait Command
  
  case class SomeCommand(...) extends Command
  case class SomeOtherCommand(...) extends Command

  def apply(id: ComponentID): Behavior[Command] = ???
```

We then define an `EntityMeta` to encapsulate the essential shardingness of `ComponentEntity`

```
  val meta = Sharding.EntityMeta[Command, ComponentID]("COMPONENT", ComponentEntity.apply(_))
```

Somewhere in the process of setting up the `ActorSystem` in this JVM, we would initialize sharding:

```
val sharding: ClusterSharding = ???

val entityTypes = Seq(
  ComponentEntity.meta.EntityDef,
  // similarly for the other entities
)

entityTypes.foreach { e =>
  sharding.init(e)
  ()
}
```

And we obtain an `EntityRef` for a `ComponentID` with:

```
val component = ComponentEntity.entityRefFor(system, componentId)
```

A serialization of the `EntityRef` is likely to contain something saying "this entity is a `ComponentEntity`"
and a stringification of its `ComponentID`, so the deserializer for an `EntityRef[ComponentEntity]` would

```
val entityType = ???
val entityId = ???

if (entityType == "COMPONENT") {
  ComponentEntity.entityRefForStringId(system, entityId)
}
```

Malformed IDs will result in an entity instance where the incarnations log errors on incarnation and on each
message.

Imagine that we have a `ContextEntity` which might track the components with a given context.  Its protocol
might look like

```
object ContextEntity {
  sealed trait Command

  case class Register(type: String, replyTo: RecipientRef[Registered]) extends Command

  case class Registered(context: String)

  def apply(id: String): Behavior[Command] = ???

  val meta = Sharding.EntityMeta[Command, String]("CONTEXT", ContextEntity.apply(_))
}
```

`ContextEntity.Registered` isn't a command for `ComponentEntity`.  But in `ComponentEntity`:

```
  val registeredAdapter: ContextEntity.Registered => Command = { r =>
    SomeOtherCommand(...)
  }

  val registeredMeta = 
    Sharding.EntityMeta[ContextEntity.Registered, ComponentID]("COMPONENT-adapted-registered", {
      (shard: ActorRef[ClusterSharding.ShardCommand], cid: ComponentID) =>
        Sharding.adapterBehavior(cid)(meta.entityRefFor)(registeredAdapter)(shard)
    })
```

And after adding `ComponentEntity.registeredMeta.EntityDef` to the `entityTypes` (see above) when setting
up the `ActorSystem`, we can then have a `ComponentEntity` register with its context (e.g. as part of a
`Behaviors.setup` block:

```
  def apply(id: ComponentID): Behavior[Command] =
    Behaviors.setup { context =>
      val contextEntity = ContextEntity.meta.entityRefForStringId(context.system, id.context)
      contextEntity ! ContextEntity.Register("component", registeredMeta.entityRefFor(context.system, id))
      ???
    }
```
