# rainfrogger

Connection profile manager for [rainfrog](https://github.com/achristmascarl/rainfrog). Handles SSH tunnels, multiple database profiles, and credential storage so you don't have to.

## Install

Requires [rainfrog](https://github.com/achristmascarl/rainfrog) to be installed.

### From source

```bash
# JVM (development)
./mill rainfrogger.run list

# Native binary (requires GRAALVM_HOME)
./mill rainfrogger.nativeImage
cp out/rainfrogger/nativeImage.dest/native-executable ~/.local/bin/rainfrogger
```

## Usage

```
rainfrogger connect <profile>   # open tunnel + launch rainfrog
rainfrogger list                # show saved profiles
rainfrogger test <profile>      # test connectivity without launching rainfrog
```

## Configuration

`~/.config/rainfrogger/config.conf` (HOCON format, automatically enforced to chmod 600)

```hocon
profiles {
  prod {
    db {
      host = "10.0.0.5"
      port = 5432
      name = "mydb"
      user = "readonly"
      password = "secret"
    }
    tunnel {
      host = "bastion.example.com"
      port = 22
      user = "ops"
      localPort = 15432  # optional, auto-assigned if omitted
    }
  }
  local {
    db {
      host = "localhost"
      port = 5432
      name = "mydb"
      user = "postgres"
      password = "dev"
    }
  }
}
```

Profiles with a `tunnel` block get an automatic SSH tunnel via control sockets. Profiles without connect directly. The tunnel uses your existing SSH config, keys, and agent.

## Build

- Scala 3.7.4 / Mill 1.1.2
- ZIO CLI + zio-config-typesafe
- GraalVM native-image for ~32MB standalone binary with 18ms startup
