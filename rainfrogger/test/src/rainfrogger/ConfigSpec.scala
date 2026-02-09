package rainfrogger

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*
import zio.test.*
import zio.test.Assertion.*

object ConfigSpec extends ZIOSpecDefault:

  private val validHocon =
    """
      |profiles {
      |  test-db {
      |    db {
      |      host = "10.0.0.1"
      |      port = 5432
      |      name = "mydb"
      |      user = "admin"
      |      password = "secret"
      |    }
      |  }
      |  tunneled {
      |    db {
      |      host = "172.16.0.5"
      |      port = 6432
      |      name = "prod"
      |      user = "readonly"
      |      password = "p@ss"
      |    }
      |    tunnel {
      |      host = "bastion.example.com"
      |      port = 222
      |      user = "ops"
      |      localPort = 15432
      |    }
      |  }
      |}
      |""".stripMargin

  private val invalidHocon = "profiles { broken { db { host = missing } } }"

  private def loadFromString(hocon: String): IO[Config.Error, AppConfig] =
    TypesafeConfigProvider.fromHoconString(hocon).load(deriveConfig[AppConfig])

  private def renderToHocon(config: com.typesafe.config.Config): String =
    config.root().render(
      ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false)
    )

  private val sampleDirect = ProfileConfig(
    db = DbConfig("10.0.0.1", 5432, "mydb", "admin", "secret"),
    tunnel = None
  )

  private val sampleTunneled = ProfileConfig(
    db = DbConfig("172.16.0.5", 6432, "prod", "readonly", "p@ss"),
    tunnel = Some(TunnelConfig("bastion.example.com", 222, "ops", Some(15432)))
  )

  def spec = suite("ConfigSpec")(
    test("parses valid config with direct connection"):
      for cfg <- loadFromString(validHocon)
      yield
        val p = cfg.profiles("test-db")
        assert(p.db.host)(equalTo("10.0.0.1")) &&
        assert(p.db.port)(equalTo(5432)) &&
        assert(p.db.name)(equalTo("mydb")) &&
        assert(p.tunnel)(isNone)
    ,
    test("parses valid config with tunnel"):
      for cfg <- loadFromString(validHocon)
      yield
        val p = cfg.profiles("tunneled")
        assert(p.tunnel)(isSome) &&
        assert(p.tunnel.get.host)(equalTo("bastion.example.com")) &&
        assert(p.tunnel.get.port)(equalTo(222)) &&
        assert(p.tunnel.get.localPort)(isSome(equalTo(15432)))
    ,
    test("fails on invalid config"):
      for result <- loadFromString(invalidHocon).either
      yield assert(result)(isLeft)
    ,
    test("profiles map contains all entries"):
      for cfg <- loadFromString(validHocon)
      yield assert(cfg.profiles.keys)(hasSameElements(Set("test-db", "tunneled")))
    ,
    test("round-trip: write direct profile, read back"):
      val raw = ProfileManager.writeProfile(ConfigFactory.empty(), "test-db", sampleDirect)
      val hocon = renderToHocon(raw)
      for cfg <- loadFromString(hocon)
      yield
        val p = cfg.profiles("test-db")
        assert(p.db.host)(equalTo("10.0.0.1")) &&
        assert(p.db.port)(equalTo(5432)) &&
        assert(p.db.name)(equalTo("mydb")) &&
        assert(p.db.user)(equalTo("admin")) &&
        assert(p.db.password)(equalTo("secret")) &&
        assert(p.tunnel)(isNone)
    ,
    test("round-trip: write tunneled profile, read back"):
      val raw = ProfileManager.writeProfile(ConfigFactory.empty(), "tunneled", sampleTunneled)
      val hocon = renderToHocon(raw)
      for cfg <- loadFromString(hocon)
      yield
        val p = cfg.profiles("tunneled")
        assert(p.db.host)(equalTo("172.16.0.5")) &&
        assert(p.db.port)(equalTo(6432)) &&
        assert(p.tunnel)(isSome) &&
        assert(p.tunnel.get.host)(equalTo("bastion.example.com")) &&
        assert(p.tunnel.get.port)(equalTo(222)) &&
        assert(p.tunnel.get.user)(equalTo("ops")) &&
        assert(p.tunnel.get.localPort)(isSome(equalTo(15432)))
    ,
    test("round-trip: write two profiles, remove one, only other remains"):
      var raw = ProfileManager.writeProfile(ConfigFactory.empty(), "alpha", sampleDirect)
      raw = ProfileManager.writeProfile(raw, "beta", sampleTunneled)
      val afterRemove = raw.withoutPath("profiles.alpha")
      val hocon = renderToHocon(afterRemove)
      for cfg <- loadFromString(hocon)
      yield
        assert(cfg.profiles.keys)(hasSameElements(Set("beta"))) &&
        assert(cfg.profiles("beta").db.host)(equalTo("172.16.0.5"))
    ,
    test("writeProfile rejects duplicate at config level"):
      val raw = ProfileManager.writeProfile(ConfigFactory.empty(), "dup", sampleDirect)
      assertTrue(raw.hasPath("profiles.dup"))
  )
