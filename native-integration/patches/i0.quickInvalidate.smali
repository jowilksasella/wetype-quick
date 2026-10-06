# Lifecycle invalidation: replace publication identity without touching any editor.
.method public final quickInvalidate()V
    .locals 3
    invoke-static {}, Landroid/os/Looper;->myLooper()Landroid/os/Looper;
    move-result-object v0
    invoke-static {}, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;
    move-result-object v1
    if-eq v0, v1, :quick_main
    new-instance v0, Ljava/lang/IllegalStateException;
    const-string v1, "Quick model invalidation requires the main thread"
    invoke-direct {v0, v1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V
    throw v0
    :quick_main
    sget-object v2, Lcom/tencent/wetype/plugin/hld/model/i0;->q:Ljava/lang/Object;
    monitor-enter v2
    :quick_try
    new-instance v0, Lcom/tencent/wetype/plugin/hld/model/Z;
    invoke-direct {v0}, Lcom/tencent/wetype/plugin/hld/model/Z;-><init>()V
    const/4 v1, 0x0
    new-array v1, v1, [Lcom/tencent/wxhld/info/PendingInput;
    invoke-virtual {v0, v1}, Lcom/tencent/wetype/plugin/hld/model/Z;->m([Lcom/tencent/wxhld/info/PendingInput;)V
    sput-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->D:Lcom/tencent/wetype/plugin/hld/model/Z;
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    sput-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->p:Ljava/util/List;
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->r:Ljava/util/ArrayList;
    invoke-virtual {v0}, Ljava/util/ArrayList;->clear()V
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->s:Ljava/util/List;
    invoke-interface {v0}, Ljava/util/List;->clear()V
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->u:Ljava/util/ArrayList;
    invoke-virtual {v0}, Ljava/util/ArrayList;->clear()V
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->v:Ljava/util/ArrayList;
    invoke-virtual {v0}, Ljava/util/ArrayList;->clear()V
    const/4 v0, 0x0
    sput-boolean v0, Lcom/tencent/wetype/plugin/hld/model/i0;->V:Z
    sput v0, Lcom/tencent/wetype/plugin/hld/model/i0;->B:I
    monitor-exit v2
    :quick_end
    .catchall {:quick_try .. :quick_end} :quick_catch
    return-void
    :quick_catch
    move-exception v0
    monitor-exit v2
    throw v0
.end method
