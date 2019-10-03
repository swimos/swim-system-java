// Copyright 2015-2019 SWIM.AI inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package swim.runtime.warp;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import swim.concurrent.Conts;
import swim.runtime.CellContext;
import swim.runtime.DownlinkModel;
import swim.runtime.DownlinkView;
import swim.runtime.LinkBinding;
import swim.runtime.LinkContext;
import swim.runtime.Metric;
import swim.runtime.NodeBinding;
import swim.runtime.WarpBinding;
import swim.runtime.WarpContext;
import swim.runtime.profile.WarpDownlinkProfile;
import swim.structure.Value;
import swim.uri.Uri;
import swim.warp.CommandMessage;
import swim.warp.Envelope;
import swim.warp.EventMessage;
import swim.warp.LinkRequest;
import swim.warp.LinkedResponse;
import swim.warp.SyncRequest;
import swim.warp.SyncedResponse;
import swim.warp.UnlinkRequest;
import swim.warp.UnlinkedResponse;

public abstract class WarpDownlinkModem<View extends DownlinkView> extends DownlinkModel<View> implements WarpBinding {
  protected final float prio;
  protected final float rate;
  protected final Value body;
  protected WarpContext linkContext;
  protected CellContext cellContext;
  protected volatile int status;

  volatile long execDelta;
  volatile long execTime;
  volatile int openDelta;
  volatile int openCount;
  volatile int closeDelta;
  volatile int closeCount;
  volatile int eventDelta;
  volatile int commandDelta;
  volatile long eventCount;
  volatile long commandCount;
  volatile long lastReportTime;

  public WarpDownlinkModem(Uri meshUri, Uri hostUri, Uri nodeUri, Uri laneUri,
                           float prio, float rate, Value body) {
    super(meshUri, hostUri, nodeUri, laneUri);
    this.prio = prio;
    this.rate = rate;
    this.body = body;
  }

  @Override
  public final WarpBinding linkWrapper() {
    return this;
  }

  @Override
  public final WarpContext linkContext() {
    return this.linkContext;
  }

  @Override
  public void setLinkContext(LinkContext linkContext) {
    this.linkContext = (WarpContext) linkContext;
  }

  @Override
  public final CellContext cellContext() {
    return this.cellContext;
  }

  @Override
  public void setCellContext(CellContext cellContext) {
    this.cellContext = cellContext;
  }

  @Override
  public final float prio() {
    return this.prio;
  }

  @Override
  public final float rate() {
    return this.rate;
  }

  @Override
  public final Value body() {
    return this.body;
  }

  @Override
  public abstract boolean keepLinked();

  @Override
  public abstract boolean keepSynced();

  public void cueDown() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      if ((oldStatus & FEEDING_DOWN) != 0) {
        newStatus = oldStatus & ~FEEDING_DOWN | PULLING_DOWN;
      } else {
        newStatus = oldStatus & ~PULLING_DOWN;
      }
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if ((oldStatus & FEEDING_DOWN) != 0) {
      this.linkContext.pullDown();
    }
  }

  @Override
  public void feedDown() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      if ((oldStatus & PULLING_DOWN) == 0) {
        newStatus = oldStatus & ~FEEDING_DOWN | PULLING_DOWN;
      } else {
        newStatus = oldStatus | FEEDING_DOWN;
      }
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if ((oldStatus & PULLING_DOWN) == 0) {
      this.linkContext.pullDown();
    }
  }

  @Override
  public void pushDown(Envelope envelope) {
    if (envelope instanceof EventMessage) {
      pushDownEvent((EventMessage) envelope);
    } else if (envelope instanceof LinkedResponse) {
      pushDownLinked((LinkedResponse) envelope);
    } else if (envelope instanceof SyncedResponse) {
      pushDownSynced((SyncedResponse) envelope);
    } else if (envelope instanceof UnlinkedResponse) {
      pushDownUnlinked((UnlinkedResponse) envelope);
    } else {
      pushDownEnvelope(envelope);
    }
  }

  protected void pushDownEvent(EventMessage message) {
    try {
      onEvent(message);
    } finally {
      cueDown();
    }
  }

  protected void pushDownLinked(LinkedResponse response) {
    try {
      didLink(response);
    } finally {
      cueDown();
    }
  }

  protected void pushDownSynced(SyncedResponse response) {
    try {
      didSync(response);
    } finally {
      cueDown();
    }
  }

  protected void pushDownUnlinked(UnlinkedResponse response) {
    didUnlink(response);
    // Don't cueDown
  }

  protected void pushDownEnvelope(Envelope envelope) {
    // nop
  }

  @Override
  public void skipDown() {
    cueDown();
  }

  protected boolean upQueueIsEmpty() {
    return true;
  }

  protected void queueUp(Value body) {
    throw new UnsupportedOperationException();
  }

  protected Value nextUpQueue() {
    return null;
  }

  protected CommandMessage nextUpQueueCommand() {
    final Value body = nextUpQueue();
    if (body != null) {
      return new CommandMessage(this.nodeUri, this.laneUri, body);
    } else {
      return null;
    }
  }

  protected Value nextUpCue() {
    return null;
  }

  protected CommandMessage nextUpCueCommand() {
    final Value body = nextUpCue();
    if (body != null) {
      return new CommandMessage(this.nodeUri, this.laneUri, body);
    } else {
      return null;
    }
  }

  public void pushUp(Value body) {
    queueUp(body);
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      newStatus = oldStatus | FEEDING_UP;
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if (oldStatus != newStatus) {
      this.linkContext.feedUp();
    }
  }

  public void cueUp() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      newStatus = oldStatus | FEEDING_UP | CUED_UP;
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if ((oldStatus & FEEDING_UP) == 0) {
      this.linkContext.feedUp();
    }
  }

  protected void feedUp() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      if ((oldStatus & CUED_UP) != 0 || !upQueueIsEmpty()) {
        newStatus = oldStatus | FEEDING_UP;
      } else {
        newStatus = oldStatus;
        break;
      }
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if (oldStatus != newStatus) {
      this.linkContext.feedUp();
    }
  }

  @Override
  public void pullUp() {
    int oldStatus;
    int newStatus;
    do  {
      oldStatus = this.status;
      if ((oldStatus & UNLINK) != 0) {
        newStatus = oldStatus & ~(UNLINK | FEEDING_UP);
      } else if ((oldStatus & SYNC) != 0) {
        newStatus = oldStatus & ~(LINK | SYNC | FEEDING_UP);
      } else if ((oldStatus & LINK) != 0) {
        newStatus = oldStatus & ~(LINK | FEEDING_UP);
      } else {
        newStatus = oldStatus & ~(CUED_UP | FEEDING_UP);
      }
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if ((oldStatus & UNLINK) != 0) {
      final UnlinkRequest request = unlinkRequest();
      pullUpUnlink(request);
      pushUp(request);
    } else if ((oldStatus & SYNC) != 0) {
      final SyncRequest request = syncRequest();
      pullUpSync(request);
      pushUp(request);
      feedUp();
    } else if ((oldStatus & LINK) != 0) {
      final LinkRequest request = linkRequest();
      pullUpLink(request);
      pushUp(request);
      feedUp();
    } else {
      CommandMessage message = nextUpQueueCommand();
      if (message == null && (oldStatus & CUED_UP) != 0) {
        message = nextUpCueCommand();
      }
      if (message != null) {
        pullUpCommand(message);
        pushUp(message);
        feedUp();
      } else {
        this.linkContext.skipUp();
      }
    }
  }

  protected void pullUpCommand(CommandMessage message) {
    onCommand(message);
  }

  protected void pullUpLink(LinkRequest request) {
    willLink(request);
  }

  protected void pullUpSync(SyncRequest request) {
    willSync(request);
  }

  protected void pullUpUnlink(UnlinkRequest request) {
    willUnlink(request);
  }

  protected void pushUp(Envelope envelope) {
    this.linkContext.pushUp(envelope);
  }

  public void link() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      if ((oldStatus & (LINKED | OPENED)) == OPENED) {
        newStatus = oldStatus | FEEDING_UP | LINKING | LINK | LINKED;
      } else {
        newStatus = oldStatus;
        break;
      }
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if ((oldStatus & FEEDING_UP) == 0 && (newStatus & FEEDING_UP) != 0 && this.linkContext != null) {
      this.linkContext.feedUp();
    }
  }

  public void sync() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      if ((oldStatus & (LINKED | OPENED)) == OPENED) {
        newStatus = oldStatus | FEEDING_UP | SYNCING | SYNC | LINKING | LINK | LINKED;
      } else {
        newStatus = oldStatus;
        break;
      }
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if ((oldStatus & FEEDING_UP) == 0 && (newStatus & FEEDING_UP) != 0 && this.linkContext != null) {
      this.linkContext.feedUp();
    }
  }

  public void unlink() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      if ((oldStatus & LINK) != 0) {
        newStatus = oldStatus & ~(FEEDING_UP | SYNCING | SYNC | LINKING | LINK | LINKED);
      } else if ((oldStatus & (UNLINKING | LINKED)) == LINKED) {
        newStatus = (oldStatus | FEEDING_UP | UNLINKING | UNLINK) & ~(SYNCING | SYNC | LINKING | LINK);
      } else {
        newStatus = oldStatus;
        break;
      }
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    if ((oldStatus & FEEDING_UP) == 0 && (newStatus & FEEDING_UP) != 0 && this.linkContext != null) {
      this.linkContext.feedUp();
    }
  }

  public void command(float prio, Value body) {
    queueUp(body);
  }

  public void command(Value body) {
    queueUp(body);
  }

  protected LinkRequest linkRequest() {
    return new LinkRequest(this.nodeUri, this.laneUri, this.prio, this.rate, this.body);
  }

  protected SyncRequest syncRequest() {
    return new SyncRequest(this.nodeUri, this.laneUri, this.prio, this.rate, this.body);
  }

  protected UnlinkRequest unlinkRequest() {
    return new UnlinkRequest(this.nodeUri, this.laneUri, this.body);
  }

  protected void didAddDownlink(View view) {
    super.didAddDownlink(view);
    OPEN_DELTA.incrementAndGet(this);
    flushMetrics();
  }

  protected void didRemoveDownlink(View view) {
    super.didRemoveDownlink(view);
    CLOSE_DELTA.incrementAndGet(this);
  }

  @Override
  public void reopen() {
    // nop
  }

  @Override
  public void openDown() {
    didOpen();
    this.linkContext.didOpenDown();
    if ((this.status & FEEDING_UP) != 0) {
      this.linkContext.feedUp();
    }
  }

  protected void didOpen() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      newStatus = oldStatus | OPENED;
    } while (oldStatus != newStatus & !STATUS.compareAndSet(this, oldStatus, newStatus));
    if (keepLinked()) {
      if (keepSynced()) {
        sync();
      } else {
        link();
      }
    }
  }

  @Override
  public void closeDown() {
    final CellContext cellContext = this.cellContext;
    if (cellContext != null) {
      cellContext.closeDownlink(this);
    }
    didClose();
    this.linkContext.didCloseDown();
  }

  protected void didClose() {
    STATUS.set(this, 0);
    removeDownlinks();
    flushMetrics();
  }

  protected void onEvent(EventMessage message) {
    EVENT_DELTA.incrementAndGet(this);
    didUpdateMetrics();
  }

  protected void onCommand(CommandMessage message) {
    COMMAND_DELTA.incrementAndGet(this);
    didUpdateMetrics();
  }

  protected void willLink(LinkRequest request) {
    // stub
  }

  protected void didLink(LinkedResponse response) {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      newStatus = oldStatus & ~LINKING;
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
  }

  protected void willSync(SyncRequest request) {
    // stub
  }

  protected void didSync(SyncedResponse response) {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      newStatus = oldStatus & ~SYNCING;
    } while (oldStatus != newStatus & !STATUS.compareAndSet(this, oldStatus, newStatus));
  }

  protected void willUnlink(UnlinkRequest request) {
    // stub
  }

  protected void didUnlink(UnlinkedResponse response) {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      newStatus = oldStatus & ~(PULLING_DOWN | UNLINKING | UNLINK | SYNCING | SYNC | LINKING | LINK | LINKED);
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
  }

  @Override
  public void didConnect() {
    if (keepLinked()) {
      if (keepSynced()) {
        sync();
      } else {
        link();
      }
    }
    super.didConnect();
  }

  @Override
  public void didDisconnect() {
    int oldStatus;
    int newStatus;
    do {
      oldStatus = this.status;
      newStatus = oldStatus & ~(FEEDING_UP | UNLINKING | UNLINK | SYNCING | SYNC | LINKING | LINK | LINKED);
    } while (oldStatus != newStatus && !STATUS.compareAndSet(this, oldStatus, newStatus));
    super.didDisconnect();
  }

  @Override
  public void didCloseUp() {
    didClose();
    super.didCloseUp();
  }

  @Override
  public void didFail(Throwable error) {
    error.printStackTrace();
    super.didFail(error);
  }

  @Override
  public void openMetaDownlink(LinkBinding downlink, NodeBinding metaDownlink) {
    final CellContext cellContext = this.cellContext;
    if (cellContext != null) {
      cellContext.openMetaDownlink(downlink, metaDownlink);
    }
  }

  @Override
  public void accumulateExecTime(long execDelta) {
    EXEC_DELTA.addAndGet(this, execDelta);
    didUpdateMetrics();
  }

  protected void didUpdateMetrics() {
    do {
      final long oldReportTime = this.lastReportTime;
      final long newReportTime = System.currentTimeMillis();
      final long dt = newReportTime - oldReportTime;
      if (dt >= Metric.REPORT_INTERVAL) {
        if (LAST_REPORT_TIME.compareAndSet(this, oldReportTime, newReportTime)) {
          try {
            reportMetrics(dt);
          } catch (Throwable error) {
            if (Conts.isNonFatal(error)) {
              didFail(error);
            } else {
              throw error;
            }
          }
          break;
        }
      } else {
        break;
      }
    } while (true);
  }

  protected void flushMetrics() {
    final long newReportTime = System.currentTimeMillis();
    final long oldReportTime = LAST_REPORT_TIME.getAndSet(this, newReportTime);
    final long dt = newReportTime - oldReportTime;
    try {
      reportMetrics(dt);
    } catch (Throwable error) {
      if (Conts.isNonFatal(error)) {
        didFail(error);
      } else {
        throw error;
      }
    }
  }

  protected void reportMetrics(long dt) {
    final CellContext cellContext = this.cellContext;
    if (cellContext != null) {
      final WarpDownlinkProfile profile = collectProfile(dt);
      cellContext.reportDown(profile);
    }
  }

  protected WarpDownlinkProfile collectProfile(long dt) {
    final long execDelta = EXEC_DELTA.getAndSet(this, 0L);
    final long execRate = (long) Math.ceil((1000.0 * (double) execDelta) / (double) dt);
    final long execTime = EXEC_TIME.addAndGet(this, execDelta);

    final int openDelta = OPEN_DELTA.getAndSet(this, 0);
    final int openCount = OPEN_COUNT.addAndGet(this, openDelta);
    final int closeDelta = CLOSE_DELTA.getAndSet(this, 0);
    final int closeCount = CLOSE_COUNT.addAndGet(this, closeDelta);
    final int eventDelta = EVENT_DELTA.getAndSet(this, 0);
    final int eventRate = (int) Math.ceil((1000.0 * (double) eventDelta) / (double) dt);
    final long eventCount = EVENT_COUNT.addAndGet(this, (long) eventDelta);
    final int commandDelta = COMMAND_DELTA.getAndSet(this, 0);
    final int commandRate = (int) Math.ceil((1000.0 * (double) commandDelta) / (double) dt);
    final long commandCount = COMMAND_COUNT.addAndGet(this, (long) commandDelta);

    return new WarpDownlinkProfile(cellAddressDown(), execDelta, execRate, execTime,
                                   openDelta, openCount, closeDelta, closeCount,
                                   eventDelta, eventRate, eventCount,
                                   commandDelta, commandRate, commandCount);
  }

  static final int OPENED = 1 << 0;
  static final int LINKED = 1 << 1;
  static final int LINK = 1 << 2;
  static final int LINKING = 1 << 3;
  static final int SYNC = 1 << 4;
  static final int SYNCING = 1 << 5;
  static final int UNLINK = 1 << 6;
  static final int UNLINKING = 1 << 7;
  static final int FEEDING_DOWN = 1 << 8;
  static final int PULLING_DOWN = 1 << 9;
  static final int CUED_UP = 1 << 10;
  static final int FEEDING_UP = 1 << 11;

  @SuppressWarnings("unchecked")
  static final AtomicIntegerFieldUpdater<WarpDownlinkModem<?>> STATUS =
      AtomicIntegerFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "status");

  @SuppressWarnings("unchecked")
  protected static final AtomicLongFieldUpdater<WarpDownlinkModem<?>> EXEC_DELTA =
      AtomicLongFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "execDelta");
  @SuppressWarnings("unchecked")
  protected static final AtomicLongFieldUpdater<WarpDownlinkModem<?>> EXEC_TIME =
      AtomicLongFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "execTime");
  @SuppressWarnings("unchecked")
  static final AtomicIntegerFieldUpdater<WarpDownlinkModem<?>> OPEN_DELTA =
      AtomicIntegerFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "openDelta");
  @SuppressWarnings("unchecked")
  static final AtomicIntegerFieldUpdater<WarpDownlinkModem<?>> OPEN_COUNT =
      AtomicIntegerFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "openCount");
  @SuppressWarnings("unchecked")
  static final AtomicIntegerFieldUpdater<WarpDownlinkModem<?>> CLOSE_DELTA =
      AtomicIntegerFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "closeDelta");
  @SuppressWarnings("unchecked")
  static final AtomicIntegerFieldUpdater<WarpDownlinkModem<?>> CLOSE_COUNT =
      AtomicIntegerFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "closeCount");
  @SuppressWarnings("unchecked")
  static final AtomicIntegerFieldUpdater<WarpDownlinkModem<?>> EVENT_DELTA =
      AtomicIntegerFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "eventDelta");
  @SuppressWarnings("unchecked")
  static final AtomicLongFieldUpdater<WarpDownlinkModem<?>> EVENT_COUNT =
      AtomicLongFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "eventCount");
  @SuppressWarnings("unchecked")
  static final AtomicIntegerFieldUpdater<WarpDownlinkModem<?>> COMMAND_DELTA =
      AtomicIntegerFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "commandDelta");
  @SuppressWarnings("unchecked")
  static final AtomicLongFieldUpdater<WarpDownlinkModem<?>> COMMAND_COUNT =
      AtomicLongFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "commandCount");
  @SuppressWarnings("unchecked")
  static final AtomicLongFieldUpdater<WarpDownlinkModem<?>> LAST_REPORT_TIME =
      AtomicLongFieldUpdater.newUpdater((Class<WarpDownlinkModem<?>>) (Class<?>) WarpDownlinkModem.class, "lastReportTime");
}
