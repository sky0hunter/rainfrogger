package rainfrogger

import zio.*
import zio.cli.*
import zio.cli.HelpDoc.Span.text

object Main extends ZIOCliDefault:

  private val profileArg = Args.text("profile")

  private val connectCmd = Command("connect", Options.none, profileArg)
    .withHelp(HelpDoc.p("Connect to a database profile (opens SSH tunnel if configured, then launches rainfrog)"))

  private val listCmd = Command("list")
    .withHelp(HelpDoc.p("List all available connection profiles"))

  private val testCmd = Command("test", Options.none, profileArg)
    .withHelp(HelpDoc.p("Test connectivity to a profile without launching rainfrog"))

  sealed trait Subcommand
  case class Connect(profile: String) extends Subcommand
  case object ListProfiles             extends Subcommand
  case class Test(profile: String)     extends Subcommand

  private val command: Command[Subcommand] =
    Command("rainfrogger").subcommands(
      connectCmd.map(Connect(_)),
      listCmd.as(ListProfiles),
      testCmd.map(Test(_))
    )

  val cliApp = CliApp.make(
    name = "rainfrogger",
    version = "0.1.0",
    summary = text("Connection profile manager for rainfrog"),
    command = command
  )(run)

  private def run(sub: Subcommand): ZIO[Any, Any, Any] = sub match
    case Connect(name) => connect(name)
    case ListProfiles  => list
    case Test(name)    => test(name)

  private def connect(name: String): ZIO[Any, Throwable, ExitCode] =
    ZIO.scoped:
      for
        profile <- AppConfig.profile(name)
        tunnel <- profile.tunnel match
                    case Some(cfg) =>
                      ZIO
                        .acquireRelease(Tunnel.start(name, profile.db, cfg))(h => Tunnel.stop(h))
                        .map(Some(_))
                    case None => ZIO.succeed(None)
        code <- Rainfrog.launch(profile.db, tunnel)
      yield code

  private val list: ZIO[Any, Throwable, Unit] =
    for
      cfg <- AppConfig.load
      _ <- ZIO.foreachDiscard(cfg.profiles.toList.sortBy(_._1)): (name, p) =>
             val tunnelInfo = p.tunnel.map(t => s" via ${t.user}@${t.host}:${t.port}").getOrElse(" (direct)")
             Console.printLine(s"  $name  ${p.db.user}@${p.db.host}:${p.db.port}/${p.db.name}$tunnelInfo")
    yield ()

  private def test(name: String): ZIO[Any, Throwable, Unit] =
    ZIO.scoped:
      for
        profile <- AppConfig.profile(name)
        tunnel <- profile.tunnel match
                    case Some(cfg) =>
                      Console.printLine(s"Opening tunnel to ${cfg.host}:${cfg.port}...") *>
                        ZIO
                          .acquireRelease(Tunnel.start(name, profile.db, cfg))(h => Tunnel.stop(h))
                          .map(Some(_))
                    case None => ZIO.succeed(None)
        _ <- Console.printLine("Testing connection...")
        _ <- Rainfrog.testConnection(profile.db, tunnel)
        _ <- Console.printLine(s"OK — $name is reachable")
      yield ()
