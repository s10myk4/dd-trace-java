package datadog.smoketest

class SpringBootGrpcAsyncTest extends SpringBootWithGRPCTest {
  private static final Set<String> EXPECTED_TRACES =
    ["[grpc.server[grpc.message]]",
     "[servlet.request[spring.handler[grpc.client[grpc.message]]]]"].toSet()

  @Override
  protected Set<String> expectedTraces() {
    return EXPECTED_TRACES
  }

  @Override
  String route() {
    return "async_greeting"
  }
}
