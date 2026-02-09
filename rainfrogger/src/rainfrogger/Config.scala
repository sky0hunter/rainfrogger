package rainfrogger

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*

case class DbConfig(
  host: String,
  port: Int,
  name: String,
  user: String,
  password: String
) derives Config

case class TunnelConfig(
  host: String,
  port: Int,
  user: String,
  localPort: Option[Int] = None
) derives Config

case class ProfileConfig(
  db: DbConfig,
  tunnel: Option[TunnelConfig] = None
) derives Config

case class AppConfig(
  profiles: Map[String, ProfileConfig]
) derives Config

object AppConfig:

  val configDir: Path  = Paths.get(sys.props("user.home"), ".config", "rainfrogger")
  val configFile: Path = configDir.resolve("config.conf")
  val tunnelDir: Path  = configDir.resolve("tunnels")

  def load: Task[AppConfig] =
    for
      _ <- ensureConfigExists
      _ <- enforcePermissions
      config <- TypesafeConfigProvider
                  .fromHoconFilePath(configFile.toString)
                  .load(deriveConfig[AppConfig])
                  .mapError(e => new RuntimeException(s"Config error: ${e.getMessage}"))
    yield config

  def profile(name: String): Task[ProfileConfig] =
    load.flatMap: cfg =>
      ZIO.fromOption(cfg.profiles.get(name)).orElseFail:
        val available = cfg.profiles.keys.mkString(", ")
        new RuntimeException(s"Profile '$name' not found. Available: $available")

  private def ensureConfigExists: Task[Unit] =
    ZIO.unless(Files.exists(configFile)):
      ZIO.fail(new RuntimeException(s"Config not found at $configFile\nCreate it with your connection profiles."))
    .unit

  private def enforcePermissions: Task[Unit] = ZIO.attempt:
    val perms    = Files.getPosixFilePermissions(configFile)
    val expected = Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).asJava
    if perms != expected then
      Files.setPosixFilePermissions(configFile, expected)
      java.lang.System.err.println(s"Fixed permissions on $configFile (set to 600)")
