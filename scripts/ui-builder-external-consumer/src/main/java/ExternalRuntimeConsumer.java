import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor;
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort;
import java.util.Arrays;

public final class ExternalRuntimeConsumer {
  private ExternalRuntimeConsumer() {}

  public static void main(String[] args) {
    require(
        UiBuilderServicePort.class.getPackageName().equals("ee.schimke.composeai.uibuilder.service"),
        "unexpected runtime package");
    require(
        Arrays.stream(UiBuilderServicePort.class.getMethods())
            .anyMatch(method -> method.getName().equals("execute")),
        "runtime service port has no execute method");
    require(
        Arrays.stream(UiBuilderServicePort.class.getMethods())
            .anyMatch(method -> method.getName().equals("subscribe")),
        "runtime service port has no subscribe method");
    require(
        AuthenticatedUiBuilderActor.class.getDeclaredMethods().length > 0,
        "runtime actor type is not usable");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}
