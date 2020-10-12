package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles JVM version.
 */
@Slf4j
@AutoService(Instrumenter.class)
public final class JavaForkJoinTaskInstrumentation extends Instrumenter.Default {

  public JavaForkJoinTaskInstrumentation() {
    super("java_concurrent", "fork-join-task");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return extendsClass(
        named(ForkJoinTask.class.getName())
            .and(
                new ElementMatcher.Junction.AbstractBase<TypeDescription>() {
                  @Override
                  public boolean matches(TypeDescription target) {
                    return !ExcludeFilter.exclude(ExcludeType.FORK_JOIN_TASK, target.getName());
                  }
                }));
  }

  @Override
  public Map<String, String> contextStoreForAll() {
    return Collections.singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformers = new HashMap<>(4);
    transformers.put(isMethod().and(named("exec")), getClass().getName() + "$Exec");
    transformers.put(isMethod().and(named("fork")), getClass().getName() + "$Fork");
    transformers.put(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
    return Collections.unmodifiableMap(transformers);
  }

  public static final class Exec {
    @Advice.OnMethodEnter
    public static <T> TraceScope before(@Advice.This ForkJoinTask<T> task) {
      return AdviceUtils.startTaskScope(
          InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      AdviceUtils.endTaskScope(scope);
    }
  }

  public static final class Fork {
    @Advice.OnMethodEnter
    public static <T> void fork(@Advice.This ForkJoinTask<T> task) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(ForkJoinTask.class, State.class)
            .putIfAbsent(task, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }

  public static final class Cancel {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static <T> void cancel(@Advice.This ForkJoinTask<T> task) {
      State state = InstrumentationContext.get(ForkJoinTask.class, State.class).get(task);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }
}
