package sk.adamko.velospawn.testkit;

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.TaskStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Deterministic Velocity scheduler driven by a virtual clock. Nothing runs until {@link #advance}. */
public final class FakeScheduler implements Scheduler {

    private long now = 1_000_000_000L;
    private long seq;
    private final List<Task> tasks = new ArrayList<>();

    public long now() {
        return now;
    }

    public void advanceSeconds(long seconds) {
        advance(TimeUnit.SECONDS.toMillis(seconds));
    }

    public void advanceMinutes(long minutes) {
        advance(TimeUnit.MINUTES.toMillis(minutes));
    }

    /** Moves time forward, running every task that falls due, in time order. */
    public void advance(long millis) {
        long target = now + millis;
        while (true) {
            Task next = null;
            for (Task t : tasks) {
                if (t.status == TaskStatus.SCHEDULED && t.due <= target
                        && (next == null || t.due < next.due || (t.due == next.due && t.seq < next.seq))) {
                    next = t;
                }
            }
            if (next == null) {
                break;
            }
            now = Math.max(now, next.due);
            if (next.repeat > 0) {
                next.due += next.repeat;
            } else {
                next.status = TaskStatus.FINISHED;
            }
            next.body.accept(next);
        }
        now = target;
        tasks.removeIf(t -> t.status != TaskStatus.SCHEDULED);
    }

    public long pendingTasks() {
        return tasks.stream().filter(t -> t.status == TaskStatus.SCHEDULED).count();
    }

    @Override
    public TaskBuilder buildTask(Object plugin, Runnable runnable) {
        return new Builder(plugin, t -> runnable.run());
    }

    @Override
    public TaskBuilder buildTask(Object plugin, Consumer<ScheduledTask> consumer) {
        return new Builder(plugin, consumer::accept);
    }

    @Override
    public Collection<ScheduledTask> tasksByPlugin(Object plugin) {
        return tasks.stream().filter(t -> t.plugin == plugin).map(t -> (ScheduledTask) t).toList();
    }

    private final class Task implements ScheduledTask {
        final Object plugin;
        final Consumer<Task> body;
        final long seq;
        long due;
        long repeat;
        TaskStatus status = TaskStatus.SCHEDULED;

        Task(Object plugin, Consumer<Task> body, long due, long repeat) {
            this.plugin = plugin;
            this.body = body;
            this.due = due;
            this.repeat = repeat;
            this.seq = FakeScheduler.this.seq++;
        }

        @Override
        public Object plugin() {
            return plugin;
        }

        @Override
        public TaskStatus status() {
            return status;
        }

        @Override
        public void cancel() {
            if (status == TaskStatus.SCHEDULED) {
                status = TaskStatus.CANCELLED;
            }
        }
    }

    private final class Builder implements TaskBuilder {
        private final Object plugin;
        private final Consumer<Task> body;
        private long delay;
        private long repeat;

        Builder(Object plugin, Consumer<Task> body) {
            this.plugin = plugin;
            this.body = body;
        }

        @Override
        public TaskBuilder delay(long time, TimeUnit unit) {
            delay = unit.toMillis(time);
            return this;
        }

        @Override
        public TaskBuilder repeat(long time, TimeUnit unit) {
            repeat = unit.toMillis(time);
            return this;
        }

        @Override
        public TaskBuilder clearDelay() {
            delay = 0;
            return this;
        }

        @Override
        public TaskBuilder clearRepeat() {
            repeat = 0;
            return this;
        }

        @Override
        public ScheduledTask schedule() {
            Task t = new Task(plugin, body, now + delay, repeat);
            tasks.add(t);
            return t;
        }
    }
}
