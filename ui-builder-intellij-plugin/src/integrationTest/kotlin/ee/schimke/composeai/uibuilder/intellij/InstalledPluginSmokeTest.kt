package ee.schimke.composeai.uibuilder.intellij

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import com.intellij.tools.ide.starter.product.android.studio.AndroidStudio
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Test
import org.kodein.di.DI
import org.kodein.di.bindSingleton

/** Launches the packaged ZIP in the two IDE products the release claims to support. */
class InstalledPluginSmokeTest {
  init {
    di = DI {
      extend(di)
      bindSingleton<CIServer>(overrides = true) {
        object : CIServer by NoCIServer {
          override fun reportTestFailure(
            testName: String,
            message: String,
            details: String,
            linkToLogs: String?,
            kind: SyntheticTestKind,
            generifyTestName: Boolean,
          ) {
            throw AssertionError("$testName failed inside the installed IDE: $message\n$details")
          }
        }
      }
    }
  }

  @Test
  fun `installed plugin opens a uid design and its tool window`() {
    when (System.getProperty("ui.builder.smoke.ide", "all")) {
      "intellij" ->
        smoke(IdeInfo.IdeaUltimate, buildNumber = "262.10968.63", name = "intellij-idea")
      "android-studio" ->
        smoke(
          androidStudio(),
          name = "android-studio",
        )
      "all" -> {
        smoke(IdeInfo.IdeaUltimate, buildNumber = "262.10968.63", name = "intellij-idea")
        smoke(
          androidStudio(),
          name = "android-studio",
        )
      }
      else -> error("unknown ui.builder.smoke.ide")
    }
  }

  // AndroidInstaller calls this field buildNumber but resolves it against the Android Studio
  // release version in JetBrains' maintained release list.
  private fun androidStudio(): IdeInfo = IdeInfo.AndroidStudio.copy(buildNumber = "2026.2.1.6")

  private fun smoke(
    ide: IdeInfo,
    buildNumber: String? = null,
    name: String,
  ) {
    val plugin = Path.of(requireNotNull(System.getProperty("path.to.build.plugin"))).absolute()
    val project = Path.of(requireNotNull(System.getProperty("ui.builder.smoke.project"))).absolute()
    var testCase = TestCase(ide, LocalProjectInfo(project))
    buildNumber?.let { testCase = testCase.withBuildNumber(it) }
    Starter.newContext(
        testName = "installed-plugin-$name",
        testCase = testCase,
      )
      .apply {
        PluginConfigurator(this).installPluginFromPath(plugin)
        applyVMOptionsPatch {
          addSystemProperty("idea.trust.all.projects", true)
          addSystemProperty("ide.show.tips.on.startup.default.value", false)
        }
      }
      .runIdeWithDriver()
      .useDriverAndCloseIde {
        waitForIndicators(5.minutes)
        invokeAction("ActivateComposeUIBuilderToolWindow")
        waitForIndicators(2.minutes)
      }
  }
}
