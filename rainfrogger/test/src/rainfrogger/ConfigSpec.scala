package rainfrogger

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
  )
