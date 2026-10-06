# Append inside com/tencent/wetype/plugin/hld/model/i0, after its existing methods.
# Fields are STATIC in the original APK. No original method is replaced here.
.method public final quickPublish(Ljava/util/ArrayList;IZLcom/tencent/wetype/plugin/hld/model/Z;ZZ)V
    .locals 10

    invoke-static {}, Landroid/os/Looper;->myLooper()Landroid/os/Looper;
    move-result-object v0
    invoke-static {}, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;
    move-result-object v1
    if-eq v0, v1, :quick_main
    new-instance v0, Ljava/lang/IllegalStateException;
    const-string v1, "Quick model publication requires the main thread"
    invoke-direct {v0, v1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V
    throw v0

    :quick_main
    if-eqz p1, :quick_null
    if-nez p4, :quick_args
    :quick_null
    new-instance v0, Ljava/lang/NullPointerException;
    const-string v1, "Quick candidates and pending state are required"
    invoke-direct {v0, v1}, Ljava/lang/NullPointerException;-><init>(Ljava/lang/String;)V
    throw v0

    :quick_args
    new-instance v1, Ljava/util/ArrayList;
    invoke-direct {v1, p1}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
    move-object p1, v1
    sget-object v7, Lcom/tencent/wetype/plugin/hld/model/i0;->q:Ljava/lang/Object;
    monitor-enter v7
    :quick_write_start
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->D:Lcom/tencent/wetype/plugin/hld/model/Z;
    invoke-virtual {v0}, Lcom/tencent/wetype/plugin/hld/model/Z;->c()Ljava/lang/CharSequence;
    move-result-object v0
    invoke-virtual {p4, v0}, Lcom/tencent/wetype/plugin/hld/model/Z;->i(Ljava/lang/CharSequence;)V
    # v6 tracks whether pending observers need an update. Pure additive prefetch
    # preserves composition and scroll: it updates cache/identity but not pending.
    move/from16 v6, p6
    if-nez v6, :quick_pending_decided
    invoke-virtual {v0}, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {p4}, Lcom/tencent/wetype/plugin/hld/model/Z;->c()Ljava/lang/CharSequence;
    move-result-object v2
    invoke-virtual {v2}, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v2
    invoke-virtual {v2, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v6
    xor-int/lit8 v6, v6, 0x1
    :quick_pending_decided

    # r/s/u/v are final Java collections. Clearing them releases list references only;
    # no native CandidateIterator, candidate ID, deletion, session or cloud call occurs.
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->r:Ljava/util/ArrayList;
    invoke-virtual {v0}, Ljava/util/ArrayList;->clear()V
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->s:Ljava/util/List;
    invoke-interface {v0}, Ljava/util/List;->clear()V
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->u:Ljava/util/ArrayList;
    invoke-virtual {v0}, Ljava/util/ArrayList;->clear()V
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->v:Ljava/util/ArrayList;
    invoke-virtual {v0}, Ljava/util/ArrayList;->clear()V

    sput-object v1, Lcom/tencent/wetype/plugin/hld/model/i0;->p:Ljava/util/List;
    sput-object p4, Lcom/tencent/wetype/plugin/hld/model/i0;->D:Lcom/tencent/wetype/plugin/hld/model/Z;
    sput-boolean p3, Lcom/tencent/wetype/plugin/hld/model/i0;->V:Z
    # B is volatile: publish it last, after the complete cache/context replacement.
    sput p2, Lcom/tencent/wetype/plugin/hld/model/i0;->B:I
    monitor-exit v7
    :quick_write_end
    .catchall {:quick_write_start .. :quick_write_end} :quick_unlock

    # Exact N5 observer ABI, with a listener snapshot and stale-publication checks.
    # NativeModelPort gives each publication a fresh Z identity. Session/mode clear
    # must also publish a fresh Z before new input is accepted.
    new-instance v9, Ljava/util/ArrayList;
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->g:Ljava/util/ArrayList;
    invoke-direct {v9, v0}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
    invoke-virtual {v9}, Ljava/util/ArrayList;->iterator()Ljava/util/Iterator;
    move-result-object v8
    :quick_candidate_next
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->D:Lcom/tencent/wetype/plugin/hld/model/Z;
    if-ne v0, p4, :quick_done
    invoke-interface {v8}, Ljava/util/Iterator;->hasNext()Z
    move-result v0
    if-eqz v0, :quick_pending_begin
    invoke-interface {v8}, Ljava/util/Iterator;->next()Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Lcom/tencent/wetype/plugin/hld/candidate/w;
    move-object v1, p1
    move v2, p2
    move/from16 v3, p6
    move v4, p3
    invoke-interface {v0, v1, v2, v3, v4}, Lcom/tencent/wetype/plugin/hld/candidate/w;->A(Ljava/util/ArrayList;IZZ)V
    goto :quick_candidate_next

    # Exact native pending observer ABI, intentionally omitting V3's report tail.
    # A listener snapshot tolerates registration changes during pending callbacks.
    :quick_pending_begin
    if-eqz v6, :quick_done
    new-instance v9, Ljava/util/ArrayList;
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->m:Ljava/util/ArrayList;
    invoke-direct {v9, v0}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
    invoke-virtual {v9}, Ljava/util/ArrayList;->iterator()Ljava/util/Iterator;
    move-result-object v8
    :quick_pending_next
    sget-object v0, Lcom/tencent/wetype/plugin/hld/model/i0;->D:Lcom/tencent/wetype/plugin/hld/model/Z;
    if-ne v0, p4, :quick_done
    invoke-interface {v8}, Ljava/util/Iterator;->hasNext()Z
    move-result v0
    if-eqz v0, :quick_done
    invoke-interface {v8}, Ljava/util/Iterator;->next()Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Lcom/tencent/wetype/plugin/hld/candidate/C;
    invoke-virtual {p4}, Lcom/tencent/wetype/plugin/hld/model/Z;->f()[Lcom/tencent/wxhld/info/PendingInput;
    move-result-object v1
    invoke-virtual {p4}, Lcom/tencent/wetype/plugin/hld/model/Z;->b()Ljava/lang/CharSequence;
    move-result-object v2
    invoke-virtual {p4}, Lcom/tencent/wetype/plugin/hld/model/Z;->e()I
    move-result v3
    move v4, p5
    const/4 v5, 0x0
    invoke-interface/range {v0 .. v5}, Lcom/tencent/wetype/plugin/hld/candidate/C;->t([Lcom/tencent/wxhld/info/PendingInput;Ljava/lang/CharSequence;IZZ)V
    goto :quick_pending_next

    :quick_done
    return-void

    :quick_unlock
    move-exception v0
    monitor-exit v7
    throw v0
.end method
