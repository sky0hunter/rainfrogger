package rainfrogger

import com.typesafe.config.ConfigValueFactory
import zio.*

object ProfileManager:

  // --- Prompt helpers ---

  def prompt(label: String, default: Option[String] = None): Task[String] =
    val suffix = default.map(d => s" [$d]").getOrElse("")
    Console.print(s"$label$suffix: ") *>
      Console.readLine.map(_.trim).map:
        case "" => default.getOrElse("")
        case v  => v

  def promptInt(label: String, default: Option[Int] = None): Task[Int] =
    prompt(label, default.map(_.toString)).flatMap: s =>
      ZIO.attempt(s.toInt).mapError(_ => new RuntimeException(s"Invalid integer: $s"))

  def promptPassword(label: String): Task[String] =
    ZIO.attempt(Option(java.lang.System.console())).flatMap:
      case Some(con) =>
        Console.print(s"$label: ") *>
          ZIO.attempt(String(con.readPassword()))
      case None =>
        prompt(label)

  def promptYesNo(label: String, default: Boolean): Task[Boolean] =
    val hint = if default then "Y/n" else "y/N"
    Console.print(s"$label [$hint]: ") *>
      Console.readLine.map(_.trim.toLowerCase).map:
        case "y" | "yes" => true
        case "n" | "no"  => false
        case ""          => default
        case _           => default

  // --- Collect profile interactively ---

  def collectProfile(defaults: Option[ProfileConfig] = None): Task[ProfileConfig] =
    for
      host <- prompt("DB host", defaults.map(_.db.host))
      port <- promptInt("DB port", Some(defaults.map(_.db.port).getOrElse(5432)))
      name <- prompt("DB name", defaults.map(_.db.name))
      user <- prompt("DB user", defaults.map(_.db.user))
      pass <- defaults match
                case Some(d) => prompt("DB password", Some(d.db.password))
                case None    => promptPassword("DB password")
      useTunnel <- promptYesNo("Use SSH tunnel?", defaults.flatMap(_.tunnel).isDefined)
      tunnel <-
        if useTunnel then
          val td = defaults.flatMap(_.tunnel)
          for
            th <- prompt("  Tunnel host", td.map(_.host))
            tp <- promptInt("  Tunnel port", Some(td.map(_.port).getOrElse(22)))
            tu <- prompt("  Tunnel user", td.map(_.user))
            lp <- prompt("  Local port (empty for auto)", td.flatMap(_.localPort).map(_.toString))
          yield Some(TunnelConfig(th, tp, tu, if lp.isEmpty then None else Some(lp.toInt)))
        else ZIO.succeed(None)
    yield ProfileConfig(DbConfig(host, port, name, user, pass), tunnel)

  // --- Config file operations ---

  def addProfile(name: String, profile: ProfileConfig): Task[Unit] =
    for
      raw <- AppConfig.loadRaw
      _ <- ZIO.when(raw.hasPath(s"profiles.$name")):
             ZIO.fail(new RuntimeException(s"Profile '$name' already exists. Use 'edit' to modify it."))
      updated = writeProfile(raw, name, profile)
      _ <- AppConfig.saveRaw(updated)
    yield ()

  def editProfile(name: String, profile: ProfileConfig): Task[Unit] =
    for
      raw <- AppConfig.loadRaw
      _ <- ZIO.unless(raw.hasPath(s"profiles.$name")):
             ZIO.fail(new RuntimeException(s"Profile '$name' not found."))
      cleaned = raw.withoutPath(s"profiles.$name")
      updated = writeProfile(cleaned, name, profile)
      _ <- AppConfig.saveRaw(updated)
    yield ()

  def removeProfile(name: String): Task[Unit] =
    for
      raw <- AppConfig.loadRaw
      _ <- ZIO.unless(raw.hasPath(s"profiles.$name")):
             ZIO.fail(new RuntimeException(s"Profile '$name' not found."))
      updated = raw.withoutPath(s"profiles.$name")
      _ <- AppConfig.saveRaw(updated)
    yield ()

  def loadExisting(name: String): Task[ProfileConfig] =
    for
      cfg <- AppConfig.load
      p <- ZIO.fromOption(cfg.profiles.get(name))
             .orElseFail(new RuntimeException(s"Profile '$name' not found."))
    yield p

  private[rainfrogger] def writeProfile(
    base: com.typesafe.config.Config,
    name: String,
    profile: ProfileConfig
  ): com.typesafe.config.Config =
    val prefix = s"profiles.$name"
    var c = base
      .withValue(s"$prefix.db.host", ConfigValueFactory.fromAnyRef(profile.db.host))
      .withValue(s"$prefix.db.port", ConfigValueFactory.fromAnyRef(profile.db.port))
      .withValue(s"$prefix.db.name", ConfigValueFactory.fromAnyRef(profile.db.name))
      .withValue(s"$prefix.db.user", ConfigValueFactory.fromAnyRef(profile.db.user))
      .withValue(s"$prefix.db.password", ConfigValueFactory.fromAnyRef(profile.db.password))
    profile.tunnel match
      case Some(t) =>
        c = c
          .withValue(s"$prefix.tunnel.host", ConfigValueFactory.fromAnyRef(t.host))
          .withValue(s"$prefix.tunnel.port", ConfigValueFactory.fromAnyRef(t.port))
          .withValue(s"$prefix.tunnel.user", ConfigValueFactory.fromAnyRef(t.user))
        t.localPort.foreach: lp =>
          c = c.withValue(s"$prefix.tunnel.localPort", ConfigValueFactory.fromAnyRef(lp))
        c
      case None => c
