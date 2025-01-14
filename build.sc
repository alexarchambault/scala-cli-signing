import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.20-2`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.20-2`

import $file.publish, publish.{finalPublishVersion, publishSonatype => publishSonatype0}

import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._, scalalib._

import java.io.File

object Deps {
  object Versions {
    def jsoniterScala = "2.13.7"
  }
  def bouncycastle    = ivy"org.bouncycastle:bcpg-jdk15on:1.68"
  def caseApp         = ivy"com.github.alexarchambault::case-app:2.1.0-M13"
  def coursierPublish = ivy"io.get-coursier.publish::publish:0.1.0"
  def expecty         = ivy"com.eed3si9n.expecty::expecty:0.15.4"
  def jsoniterCore =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:${Versions.jsoniterScala}"
  def jsoniterMacros =
    ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:${Versions.jsoniterScala}"
  def munit = ivy"org.scalameta::munit:0.7.29"
  def osLib = ivy"com.lihaoyi::os-lib:0.8.1"
  def svm   = ivy"org.graalvm.nativeimage:svm:$graalVmVersion"

  def graalVmVersion  = "22.0.0"
  def graalVmId       = s"graalvm-java17:$graalVmVersion"
  def csDockerVersion = "2.1.0-M5-18-gfebf9838c"
}

object Scala {
  def scala213 = "2.13.8"
}

def ghOrg  = "scala-cli"
def ghName = "scala-cli-signing"
trait ScalaCliSigningPublish extends PublishModule {
  import mill.scalalib.publish._
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "io.github.alexarchambault.scala-cli.signing",
    url = s"https://github.com/$ghOrg/$ghName",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github(ghOrg, ghName),
    developers = Seq(
      Developer("alexarchambault", "Alex Archambault", "https://github.com/alexarchambault")
    )
  )
  def publishVersion = finalPublishVersion()
}

object shared extends ScalaModule with ScalaCliSigningPublish {
  def scalaVersion = Scala.scala213
  def ivyDeps = super.ivyDeps() ++ Seq(
    Deps.jsoniterCore,
    Deps.osLib
  )
  def compileIvyDeps = super.ivyDeps() ++ Seq(
    Deps.jsoniterMacros
  )
}

object `cli-options` extends ScalaModule with ScalaCliSigningPublish {
  def scalaVersion = Scala.scala213
  def ivyDeps = super.ivyDeps() ++ Seq(
    Deps.caseApp
  )
  def moduleDeps = Seq(
    shared
  )
}

trait CliNativeImage extends NativeImage {
  def nativeImagePersist      = System.getenv("CI") != null
  def nativeImageGraalVmJvmId = Deps.graalVmId
  def nativeImageName         = "scala-cli-signing"
  def nativeImageClassPath    = `native-cli`.runClasspath()
  def nativeImageMainClass = T {
    `native-cli`.mainClass().getOrElse(sys.error("no main class found"))
  }
  def nativeImageOptions = super.nativeImageOptions() ++ Seq(
    "--no-fallback",
    "--rerun-class-initialization-at-runtime=org.bouncycastle.jcajce.provider.drbg.DRBG$Default,org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV"
  )

  def nameSuffix = ""
  def copyToArtifacts(directory: String = "artifacts/") = T.command {
    val _ = Upload.copyLauncher(
      nativeImage().path,
      directory,
      "scala-cli-signing",
      compress = true,
      suffix = nameSuffix
    )
  }
}

object cli extends ScalaModule with ScalaCliSigningPublish { self =>
  def scalaVersion = Scala.scala213
  def ivyDeps = super.ivyDeps() ++ Seq(
    Deps.bouncycastle,
    Deps.caseApp,
    Deps.coursierPublish // we can probably get rid of that one
  )
  def moduleDeps = Seq(
    `cli-options`
  )
  def mainClass = Some("scala.cli.signing.ScalaCliSigning")
}
object `native-cli` extends ScalaModule with ScalaCliSigningPublish { self =>
  def scalaVersion = Scala.scala213
  def ivyDeps = super.ivyDeps() ++ Seq(
    Deps.svm
  )
  def moduleDeps = Seq(
    cli
  )

  def mainClass = cli.mainClass()

  object `base-image` extends CliNativeImage
  object `static-image` extends CliNativeImage {
    private def helperImageName = "scala-cli-signing-musl"
    def nativeImageDockerParams = T {
      buildHelperImage()
      Some(
        NativeImage.linuxStaticParams(
          s"$helperImageName:latest",
          s"https://github.com/coursier/coursier/releases/download/v${Deps.csDockerVersion}/cs-x86_64-pc-linux.gz"
        )
      )
    }
    def buildHelperImage = T {
      os.proc("docker", "build", "-t", helperImageName, ".")
        .call(cwd = os.pwd / "project" / "musl-image", stdout = os.Inherit)
      ()
    }
    def writeNativeImageScript(scriptDest: String, imageDest: String = "") = T.command {
      buildHelperImage()
      super.writeNativeImageScript(scriptDest, imageDest)()
    }
    def nameSuffix = "-static"
  }

  object `mostly-static-image` extends CliNativeImage {
    def nativeImageDockerParams = Some(
      NativeImage.linuxMostlyStaticParams(
        "ubuntu:18.04", // TODO Pin that
        s"https://github.com/coursier/coursier/releases/download/v${Deps.csDockerVersion}/cs-x86_64-pc-linux.gz"
      )
    )
    def nameSuffix = "-mostly-static"
  }
}

def tmpDirBase = T.persistent {
  PathRef(T.dest / "working-dir")
}

trait CliTests extends ScalaModule {
  def testLauncher: T[PathRef]
  def cliKind: T[String]

  def scalaVersion = Scala.scala213

  def prefix = "integration-"
  private def updateRef(name: String, ref: PathRef): PathRef = {
    val rawPath = ref.path.toString.replace(
      File.separator + name + File.separator,
      File.separator
    )
    PathRef(os.Path(rawPath))
  }
  private def mainArtifactName = T(artifactName())
  def modulesPath = T {
    val name                = mainArtifactName().stripPrefix(prefix)
    val baseIntegrationPath = os.Path(millSourcePath.toString.stripSuffix(name))
    val p = os.Path(
      baseIntegrationPath.toString.stripSuffix(baseIntegrationPath.baseName)
    )
    PathRef(p)
  }
  def sources = T.sources {
    val mainPath = PathRef(modulesPath().path / "integration" / "src" / "main" / "scala")
    super.sources() ++ Seq(mainPath)
  }
  def resources = T.sources {
    val mainPath = PathRef(modulesPath().path / "integration" / "src" / "main" / "resources")
    super.resources() ++ Seq(mainPath)
  }

  trait Tests extends super.Tests {
    def ivyDeps = super.ivyDeps() ++ Agg(
      Deps.expecty,
      Deps.munit
    )
    def testFramework = "munit.Framework"
    def forkArgs      = super.forkArgs() ++ Seq("-Xmx512m", "-Xms128m")
    def forkEnv = super.forkEnv() ++ Seq(
      "SIGNING_CLI"      -> testLauncher().path.toString,
      "SIGNING_CLI_KIND" -> cliKind(),
      "SIGNING_CLI_TMP"  -> tmpDirBase().path.toString
    )

    def sources = T.sources {
      val name = mainArtifactName().stripPrefix(prefix)
      super.sources().flatMap { ref =>
        Seq(updateRef(name, ref), ref)
      }
    }
    def resources = T.sources {
      val name = mainArtifactName().stripPrefix(prefix)
      super.resources().flatMap { ref =>
        Seq(updateRef(name, ref), ref)
      }
    }
  }
}

object integration extends Module {
  object jvm extends CliTests {
    def testLauncher = cli.launcher()
    def cliKind      = "jvm"

    object test extends Tests
  }

  object native extends CliTests {
    def testLauncher = `native-cli`.`base-image`.nativeImage()
    def cliKind      = "native"

    object test extends Tests
  }
  object static extends CliTests {
    def testLauncher = `native-cli`.`static-image`.nativeImage()
    def cliKind      = "native-static"

    object test extends Tests
  }
  object `mostly-static` extends CliTests {
    def testLauncher = `native-cli`.`mostly-static-image`.nativeImage()
    def cliKind      = "native-mostly-static"

    object test extends Tests
  }
}

object ci extends Module {
  def upload(directory: String = "artifacts/") = T.command {
    val version = finalPublishVersion()

    val path = os.Path(directory, os.pwd)
    val launchers = os.list(path).filter(os.isFile(_)).map { path =>
      path.toNIO -> path.last
    }
    val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val (tag, overwriteAssets) =
      if (version.endsWith("-SNAPSHOT")) ("launchers", true)
      else ("v" + version, false)

    Upload.upload(
      ghOrg,
      ghName,
      ghToken,
      tag,
      dryRun = false,
      overwrite = overwriteAssets
    )(launchers: _*)
  }

  def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) = T.command {
    publishSonatype0(
      data = define.Target.sequence(tasks.value)(),
      log = T.ctx().log
    )
  }
}
