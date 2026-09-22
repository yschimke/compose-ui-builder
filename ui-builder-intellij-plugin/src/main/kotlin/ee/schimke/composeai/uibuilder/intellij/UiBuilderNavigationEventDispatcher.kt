package ee.schimke.composeai.uibuilder.intellij

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import ee.schimke.composeai.uibuilder.LocalDeviceSceneRoot

/** Supplies the navigation-event owner Compose Desktop normally installs at an application root. */
@Composable
internal fun ProvideUiBuilderNavigationEventDispatcher(content: @Composable () -> Unit) {
  val dispatcher = remember { NavigationEventDispatcher() }
  val owner =
    remember(dispatcher) {
      object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = dispatcher
      }
    }
  DisposableEffect(dispatcher) { onDispose { dispatcher.dispose() } }
  CompositionLocalProvider(
    LocalNavigationEventDispatcherOwner provides owner,
    LocalDeviceSceneRoot provides
      { sceneContent ->
        ProvideUiBuilderNavigationEventDispatcher(content = sceneContent)
      },
    content = content,
  )
}
