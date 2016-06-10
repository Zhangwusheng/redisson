/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.redisson.RedissonFuture;
import org.redisson.client.RedisAskException;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisLoadingException;
import org.redisson.client.RedisMovedException;
import org.redisson.client.RedisTimeoutException;
import org.redisson.client.WriteRedisConnectionException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.BatchCommandData;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.connection.ConnectionManager;
import org.redisson.connection.NodeSource;
import org.redisson.connection.NodeSource.Redirect;
import org.redisson.core.RFuture;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.internal.PlatformDependent;

public class CommandBatchService extends CommandReactiveService {

    public static class Entry {

        Queue<BatchCommandData<?, ?>> commands = PlatformDependent.newMpscQueue();

        volatile boolean readOnlyMode = true;

        public Queue<BatchCommandData<?, ?>> getCommands() {
            return commands;
        }

        public void setReadOnlyMode(boolean readOnlyMode) {
            this.readOnlyMode = readOnlyMode;
        }

        public boolean isReadOnlyMode() {
            return readOnlyMode;
        }
        
        public void clearErrors() {
            for (BatchCommandData<?, ?> commandEntry : commands) {
                commandEntry.clearError();
            }
        }

    }

    private final AtomicInteger index = new AtomicInteger();

    private ConcurrentMap<Integer, Entry> commands = PlatformDependent.newConcurrentHashMap();

    private volatile boolean executed;

    public CommandBatchService(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected <V, R> void async(boolean readOnlyMode, NodeSource nodeSource,
            Codec codec, RedisCommand<V> command, Object[] params, RedissonFuture<R> mainPromise, int attempt) {
        if (executed) {
            throw new IllegalStateException("Batch already has been executed!");
        }
        Entry entry = commands.get(nodeSource.getSlot());
        if (entry == null) {
            entry = new Entry();
            Entry oldEntry = commands.putIfAbsent(nodeSource.getSlot(), entry);
            if (oldEntry != null) {
                entry = oldEntry;
            }
        }

        if (!readOnlyMode) {
            entry.setReadOnlyMode(false);
        }
        
        BatchCommandData<V, R> commandData = new BatchCommandData<V, R>(mainPromise, codec, command, params, index.incrementAndGet());
        entry.getCommands().add(commandData);
    }

    public List<?> execute() {
        return get(executeAsync());
    }

    public RFuture<Void> executeAsyncVoid() {
        if (executed) {
            throw new IllegalStateException("Batch already executed!");
        }

        if (commands.isEmpty()) {
            return connectionManager.newSucceededFuture(null);
        }
        executed = true;

        RedissonFuture<Void> voidPromise = connectionManager.newPromise();
        voidPromise.handle((r, ex) -> {
            commands = null;
			return null;
        });

        AtomicInteger slots = new AtomicInteger(commands.size());
        for (java.util.Map.Entry<Integer, Entry> e : commands.entrySet()) {
            execute(e.getValue(), new NodeSource(e.getKey()), voidPromise, slots, 0);
        }
        return voidPromise;
    }

    public RFuture<List<?>> executeAsync() {
        if (executed) {
            throw new IllegalStateException("Batch already executed!");
        }

        if (commands.isEmpty()) {
            return connectionManager.newSucceededFuture(null);
        }
        executed = true;

        RedissonFuture<Void> voidPromise = connectionManager.newPromise();
        RedissonFuture<List<?>> promise = connectionManager.newPromise();
        voidPromise.thenAccept(r -> {
            List<BatchCommandData> entries = new ArrayList<BatchCommandData>();
            for (Entry e : commands.values()) {
                entries.addAll(e.getCommands());
            }
            Collections.sort(entries);
            List<Object> result = new ArrayList<Object>(entries.size());
            for (BatchCommandData<?, ?> commandEntry : entries) {
                result.add(commandEntry.getPromise().getNow());
            }
            promise.complete(result);
            commands = null;
        }).exceptionally(cause -> {
            promise.completeExceptionally(cause);
            commands = null;
            return null;
        });
        
        AtomicInteger slots = new AtomicInteger(commands.size());
        for (java.util.Map.Entry<Integer, Entry> e : commands.entrySet()) {
            execute(e.getValue(), new NodeSource(e.getKey()), voidPromise, slots, 0);
        }
        return promise;
    }

    public void execute(final Entry entry, final NodeSource source, final RedissonFuture<Void> mainPromise, final AtomicInteger slots, final int attempt) {
        if (mainPromise.isCancelled()) {
            return;
        }

        if (!connectionManager.getShutdownLatch().acquire()) {
            mainPromise.completeExceptionally(new IllegalStateException("Redisson is shutdown"));
            return;
        }

        final RedissonFuture<Void> attemptPromise = connectionManager.newPromise();

        final AsyncDetails details = new AsyncDetails();

        final RFuture<RedisConnection> connectionFuture;
        if (entry.isReadOnlyMode()) {
            connectionFuture = connectionManager.connectionReadOp(source, null);
        } else {
            connectionFuture = connectionManager.connectionWriteOp(source, null);
        }

        final TimerTask retryTimerTask = new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                if (attemptPromise.isDone()) {
                    return;
                }

                if (connectionFuture.cancel(false)) {
                    connectionManager.getShutdownLatch().release();
                } else {
                    if (connectionFuture.isSuccess()) {
                        ChannelFuture writeFuture = details.getWriteFuture();
                        if (writeFuture != null && !writeFuture.cancel(false) && writeFuture.isSuccess()) {
                            return;
                        }
                    }
                }

                if (mainPromise.isCancelled()) {
                    attemptPromise.cancel(false);
                    return;
                }

                if (attempt == connectionManager.getConfig().getRetryAttempts()) {
                    if (details.getException() == null) {
                        details.setException(new RedisTimeoutException("Batch command execution timeout"));
                    }
                    attemptPromise.completeExceptionally(details.getException());
                    return;
                }
                if (!attemptPromise.cancel(false)) {
                    return;
                }

                int count = attempt + 1;
                execute(entry, source, mainPromise, slots, count);
            }
        };

        Timeout timeout = connectionManager.newTimeout(retryTimerTask, connectionManager.getConfig().getRetryInterval()*100, TimeUnit.MILLISECONDS);
        details.setTimeout(timeout);

        if (connectionFuture.isDone()) {
            checkConnectionFuture(entry, source, mainPromise, attemptPromise, details, connectionFuture);
        } else {
            connectionFuture.handle((r, ex) -> {
                checkConnectionFuture(entry, source, mainPromise, attemptPromise, details, connectionFuture);
                return null;
            });
        }

        attemptPromise.addListener(new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                details.getTimeout().cancel();
                if (future.isCancelled()) {
                    return;
                }

                if (future.cause() instanceof RedisMovedException) {
                    RedisMovedException ex = (RedisMovedException)future.cause();
                    entry.clearErrors();
                    execute(entry, new NodeSource(ex.getSlot(), ex.getAddr(), Redirect.MOVED), mainPromise, slots, attempt);
                    return;
                }
                if (future.cause() instanceof RedisAskException) {
                    RedisAskException ex = (RedisAskException)future.cause();
                    entry.clearErrors();
                    execute(entry, new NodeSource(ex.getSlot(), ex.getAddr(), Redirect.ASK), mainPromise, slots, attempt);
                    return;
                }
                if (future.cause() instanceof RedisLoadingException) {
                    entry.clearErrors();
                    execute(entry, source, mainPromise, slots, attempt);
                    return;
                }

                if (future.isSuccess()) {
                    if (slots.decrementAndGet() == 0) {
                        mainPromise.complete(future.getNow());
                    }
                } else {
                    mainPromise.completeExceptionally(future.cause());
                }
            }
        });
    }

    private void checkWriteFuture(final RedissonFuture<Void> attemptPromise, AsyncDetails details,
            final RedisConnection connection, ChannelFuture future) {
        if (attemptPromise.isDone() || future.isCancelled()) {
            return;
        }

        if (!future.isSuccess()) {
            details.setException(new WriteRedisConnectionException("Can't write command batch to channel: " + future.channel(), future.cause()));
        } else {
            details.getTimeout().cancel();
            TimerTask timeoutTask = new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    attemptPromise.completeExceptionally(
                            new RedisTimeoutException("Redis server response timeout during command batch execution. Channel: " + connection.getChannel()));
                }
            };
            Timeout timeout = connectionManager.newTimeout(timeoutTask, connectionManager.getConfig().getTimeout(), TimeUnit.MILLISECONDS);
            details.setTimeout(timeout);
        }
    }

    private void checkConnectionFuture(final Entry entry, final NodeSource source,
            final RedissonFuture<Void> mainPromise, final RedissonFuture<Void> attemptPromise, final AsyncDetails details,
            RFuture<RedisConnection> connFuture) {
        if (attemptPromise.isDone() || mainPromise.isCancelled() || connFuture.isCancelled()) {
            return;
        }

        if (!connFuture.isSuccess()) {
            connectionManager.getShutdownLatch().release();
            details.setException(convertException(connFuture));
            return;
        }

        final RedisConnection connection = connFuture.getNow();

        List<CommandData<?, ?>> list = new ArrayList<CommandData<?, ?>>(entry.getCommands().size() + 1);
        if (source.getRedirect() == Redirect.ASK) {
            RedissonFuture<Void> promise = connectionManager.newPromise();
            list.add(new CommandData<Void, Void>(promise, StringCodec.INSTANCE, RedisCommands.ASKING, new Object[] {}));
        } 
        for (BatchCommandData<?, ?> c : entry.getCommands()) {
            if (c.getPromise().isSuccess()) {
                // skip successful commands
                continue;
            }
            list.add(c);
        }
        ChannelFuture future = connection.send(new CommandsData(attemptPromise, list));
        details.setWriteFuture(future);

        if (details.getWriteFuture().isDone()) {
            checkWriteFuture(attemptPromise, details, connection, details.getWriteFuture());
        } else {
            details.getWriteFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    checkWriteFuture(attemptPromise, details, connection, future);
                }
            });
        }

        releaseConnection(source, connFuture, entry.isReadOnlyMode(), attemptPromise, details);
    }

}
