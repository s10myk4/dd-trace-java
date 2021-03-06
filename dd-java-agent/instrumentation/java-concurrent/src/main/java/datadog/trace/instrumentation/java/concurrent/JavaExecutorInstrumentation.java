package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JavaExecutorInstrumentation extends AbstractExecutorInstrumentation {

  @Override
  public Map<String, String> contextStoreForAll() {
    final Map<String, String> map = new HashMap<>();
    map.put(Runnable.class.getName(), State.class.getName());
    map.put(Callable.class.getName(), State.class.getName());
    map.put(Future.class.getName(), State.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("execute").and(takesArgument(0, Runnable.class)).and(takesArguments(1)),
        JavaExecutorInstrumentation.class.getName() + "$SetExecuteRunnableStateAdvice");
    return transformers;
  }

  public static class SetExecuteRunnableStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task instanceof RunnableFuture) {
        return null;
      }
      // there are cased like ScheduledExecutorService.submit (which we instrument)
      // which calls ScheduledExecutorService.schedule (which we also instrument)
      // where all of this could be dodged the second time
      final TraceScope scope = activeScope();
      if (null != scope) {
        final Runnable newTask = RunnableWrapper.wrapIfNeeded(task);
        // It is important to check potentially wrapped task if we can instrument task in this
        // executor. Some executors do not support wrapped tasks.
        if (ExecutorInstrumentationUtils.shouldAttachStateToTask(newTask, executor)) {
          task = newTask;
          final ContextStore<Runnable, State> contextStore =
              InstrumentationContext.get(Runnable.class, State.class);
          return ExecutorInstrumentationUtils.setupState(contextStore, newTask, scope);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable) {
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(executor, state, throwable);
    }
  }
}
