// Targeted compound-operation fixes. These are deliberately separate from C1
// allocation swaps: each method group shares one monitor or one detached batch.
// No mod file is changed. See docs/MOD_COMPATIBILITY_FIXES.md for scope and tests.
var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var O = Java.type('org.objectweb.asm.Opcodes');
var ClassNode = Java.type('org.objectweb.asm.tree.ClassNode');
var ClassReader = Java.type('org.objectweb.asm.ClassReader');
var ClassWriter = Java.type('org.objectweb.asm.ClassWriter');
var MethodNode = Java.type('org.objectweb.asm.tree.MethodNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var TypeInsnNode = Java.type('org.objectweb.asm.tree.TypeInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var FieldNode = Java.type('org.objectweb.asm.tree.FieldNode');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');
var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
var FrameNode = Java.type('org.objectweb.asm.tree.FrameNode');
var TryCatchBlockNode = Java.type('org.objectweb.asm.tree.TryCatchBlockNode');

var PATHS = 'mekanism/common/content/transporter/PathfinderCache';
var RECIPES = 'net/p3pp3rf1y/sophisticatedcore/util/RecipeHelper';
var BATCH = 'com/brandon3055/draconicevolution/network/CrystalUpdateBatcher';
var CRYSTAL_TILE = 'com/brandon3055/draconicevolution/blocks/energynet/tileentity/TileCrystalBase';
var MAP = 'Ljava/util/Map;';
var FLUX = 'sonar/fluxnetworks/common/connection/FluxNetwork';
var SERVER_FLUX = 'sonar/fluxnetworks/common/connection/ServerFluxNetwork';

function requireMethod(c, name, desc, isStatic) {
    var found = null;
    for (var i = 0; i < c.methods.size(); i++) {
        var m = c.methods.get(i);
        if (m.name == name && m.desc == desc) {
            if (found != null || ((m.access & O.ACC_STATIC) != 0) != isStatic
                    || (m.access & (O.ACC_ABSTRACT | O.ACC_NATIVE)) != 0)
                throw new Error('Unsupported method ' + name + desc);
            found = m;
        }
    }
    if (found == null) throw new Error('Missing method ' + name + desc);
    return found;
}

function requireField(c, name, desc, isStatic) {
    for (var i = 0; i < c.fields.size(); i++) {
        var f = c.fields.get(i);
        if (f.name == name && f.desc == desc && ((f.access & O.ACC_STATIC) != 0) == isStatic) return f;
    }
    throw new Error('Missing field ' + name + desc);
}

function noHelperCollision(c) {
    for (var i = 0; i < c.methods.size(); i++) {
        if (String(c.methods.get(i).name).indexOf('nestworld$') == 0)
            throw new Error('Compatibility helpers already present');
    }
}

function sync(m) { m.access = m.access | O.ACC_SYNCHRONIZED; }

function patchPaths(c) {
    requireField(c, 'cachedPaths', MAP, true);
    var transmitter = 'Lmekanism/common/content/network/transmitter/LogisticalTransporterBase;';
    // The cache, its nested maps, and invalidation use the SAME class monitor.
    // No monitor is held while the caller runs pathfinding or transfers an item.
    sync(requireMethod(c, 'onChanged', '([Lmekanism/common/content/network/InventoryNetwork;)V', true));
    sync(requireMethod(c, 'addCachedPath', '(' + transmitter + 'L' + PATHS + '$PathData;Ljava/util/List;D)V', true));
    sync(requireMethod(c, 'getCache', '(' + transmitter + 'Lnet/minecraft/core/BlockPos;Ljava/util/Set;)L' + PATHS + '$CachedPath;', true));
    sync(requireMethod(c, 'reset', '()V', true));
}

function patchRecipes(c) {
    var cache = RECIPES + '$RecipeCache';
    requireField(c, 'serverCache', 'L' + cache + ';', true);
    requireField(c, 'clientCache', 'L' + cache + ';', true);
    sync(requireMethod(c, 'getCache', '()L' + cache + ';', true));
    sync(requireMethod(c, 'getFromCache', '(Ljava/util/function/Function;Ljava/lang/Object;)Ljava/lang/Object;', true));
    sync(requireMethod(c, 'setLevel', '(Lnet/minecraft/world/level/Level;)V', true));
    // Clear under the cache-operation monitor, then notify listeners OUTSIDE it.
    // Do not synchronize runOnCache: it calls arbitrary recipe-change listeners.
    var count = 0;
    for (var i = 0; i < c.methods.size(); i++) {
        var m = c.methods.get(i), nodes = m.instructions.toArray();
        for (var j = 0; j < nodes.length; j++) {
            var n = nodes[j];
            if (n.getOpcode() == O.INVOKEVIRTUAL && n.owner == cache && n.name == 'clearCache' && n.desc == '()V') {
                m.instructions.set(n, new MethodInsnNode(O.INVOKESTATIC, RECIPES,
                        'nestworld$clearCache', '(L' + cache + ';)V', false));
                count++;
            }
        }
    }
    if (count != 2) throw new Error('Expected two recipe invalidation sites, got ' + count);
    var clear = new MethodNode(O.ACC_PRIVATE | O.ACC_STATIC | O.ACC_SYNCHRONIZED | O.ACC_SYNTHETIC,
            'nestworld$clearCache', '(L' + cache + ';)V', null, null);
    clear.visitVarInsn(O.ALOAD, 0);
    clear.visitMethodInsn(O.INVOKEVIRTUAL, cache, 'clearCache', '()V', false);
    clear.visitInsn(O.RETURN);
    clear.visitMaxs(1, 1);
    c.methods.add(clear);
}

function patchBatch(c) {
    // This public map is populated by TileCrystalBase.onLoad/onChunkUnloaded on
    // both logical sides. Dedicated servers never consume it, but region
    // threads still mutate the same global HashMap concurrently. Make the
    // container safe before replacing the torn lifecycle operations below.
    requireField(c, 'ID_CRYSTAL_MAP', MAP, true);
    var clinit = requireMethod(c, '<clinit>', '()V', true);
    var initNodes = clinit.instructions.toArray(), registryAllocations = 0;
    for (var ai = 0; ai < initNodes.length; ai++) {
        var allocation = initNodes[ai];
        if (allocation.getOpcode() != O.NEW || allocation.desc != 'java/util/HashMap') continue;
        var dup = allocation.getNext(), ctor = dup == null ? null : dup.getNext();
        var store = ctor == null ? null : ctor.getNext();
        if (dup == null || dup.getOpcode() != O.DUP || ctor == null
                || ctor.getOpcode() != O.INVOKESPECIAL || ctor.owner != 'java/util/HashMap'
                || ctor.name != '<init>' || ctor.desc != '()V' || store == null
                || store.getOpcode() != O.PUTSTATIC || store.owner != BATCH
                || store.name != 'ID_CRYSTAL_MAP' || store.desc != MAP) continue;
        clinit.instructions.set(allocation, new TypeInsnNode(O.NEW, 'java/util/concurrent/ConcurrentHashMap'));
        clinit.instructions.set(ctor, new MethodInsnNode(O.INVOKESPECIAL,
                'java/util/concurrent/ConcurrentHashMap', '<init>', '()V', false));
        registryAllocations++;
    }
    if (registryAllocations != 1)
        throw new Error('Expected one ID_CRYSTAL_MAP HashMap allocation, got ' + registryAllocations);

    var field = requireField(c, 'batchQue', MAP, true);
    var enqueue = requireMethod(c, 'queData', '(L' + BATCH + '$BatchedCrystalUpdate;Lnet/minecraft/server/level/ServerPlayer;)V', true);
    var send = requireMethod(c, 'tickEnd', '()V', true);
    // The field is private: only enqueue and takeBatch may access the active map.
    if ((field.access & O.ACC_PRIVATE) == 0) throw new Error('batchQue must be private');
    var reads = 0;
    for (var mi = 0; mi < c.methods.size(); mi++) {
        var method = c.methods.get(mi), insns = method.instructions.toArray();
        for (var ni = 0; ni < insns.length; ni++) {
            var insn = insns[ni];
            if ((insn.getOpcode() == O.GETSTATIC || insn.getOpcode() == O.PUTSTATIC)
                    && insn.owner == BATCH && insn.name == 'batchQue') {
                if (method !== send && method !== enqueue && method.name != '<clinit>')
                    throw new Error('Unexpected batch accessor: ' + method.name);
                if (method === send) {
                    if (insn.getOpcode() != O.GETSTATIC || insn.desc != MAP)
                        throw new Error('Unexpected batch writer');
                    reads++;
                }
            }
        }
    }
    if (reads != 4) throw new Error('Expected four batch reads, got ' + reads);
    sync(enqueue);
    field.access = field.access & ~O.ACC_FINAL;
    // Move original serialization to sendBatch(Map). Expanded stack-map frames
    // and local slots gain the Map argument at slot zero; no branch is added.
    send.name = 'nestworld$sendBatch';
    send.desc = '(' + MAP + ')V';
    send.access = O.ACC_PRIVATE | O.ACC_STATIC | O.ACC_SYNTHETIC;
    var nodes = send.instructions.toArray();
    for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        if (n.getType() == 2 || n.getType() == 10) n.var = n.var + 1; // VAR / IINC
        if (n.getType() == 14) { // expanded F_NEW frame
            n.local.add(0, 'java/util/Map');
        }
        if (n.getOpcode() == O.GETSTATIC && n.owner == BATCH && n.name == 'batchQue')
            send.instructions.set(n, new VarInsnNode(O.ALOAD, 0));
    }
    if (send.localVariables != null) {
        for (var l = 0; l < send.localVariables.size(); l++) send.localVariables.get(l).index++;
    }
    // No local-variable type annotations occur in the supported jar.
    if ((send.visibleLocalVariableAnnotations != null && !send.visibleLocalVariableAnnotations.isEmpty())
            || (send.invisibleLocalVariableAnnotations != null && !send.invisibleLocalVariableAnnotations.isEmpty()))
        throw new Error('Unsupported local variable annotations');
    send.maxLocals++;
    var take = new MethodNode(O.ACC_PRIVATE | O.ACC_STATIC | O.ACC_SYNCHRONIZED | O.ACC_SYNTHETIC,
            'nestworld$takeBatch', '()' + MAP, null, null);
    take.visitFieldInsn(O.GETSTATIC, BATCH, 'batchQue', MAP);
    take.visitTypeInsn(O.NEW, 'java/util/HashMap');
    take.visitInsn(O.DUP);
    take.visitMethodInsn(O.INVOKESPECIAL, 'java/util/HashMap', '<init>', '()V', false);
    take.visitFieldInsn(O.PUTSTATIC, BATCH, 'batchQue', MAP);
    take.visitInsn(O.ARETURN);
    take.visitMaxs(3, 0);
    c.methods.add(take);

    // A send failure used to leave the original map intact because clear() was
    // after serialization. Preserve that retry behavior after detaching. Newer
    // arrivals are appended after the older detached entries.
    var restore = new MethodNode(O.ACC_PRIVATE | O.ACC_STATIC | O.ACC_SYNCHRONIZED | O.ACC_SYNTHETIC,
            'nestworld$restoreBatch', '(' + MAP + ')V', null, null);
    restore.visitVarInsn(O.ALOAD, 0);
    restore.visitFieldInsn(O.GETSTATIC, BATCH, 'batchQue', MAP);
    restore.visitMethodInsn(O.INVOKESTATIC, 'net/nestworld/compat/NestworldCoremodSupport',
            'mergeDetachedBatch', '(' + MAP + MAP + ')' + MAP, false);
    restore.visitFieldInsn(O.PUTSTATIC, BATCH, 'batchQue', MAP);
    restore.visitInsn(O.RETURN);
    restore.visitMaxs(2, 1);
    c.methods.add(restore);

    var tick = new MethodNode(O.ACC_PUBLIC | O.ACC_STATIC, 'tickEnd', '()V', null, null);
    // Preserve externally visible method annotations on the original entry point.
    tick.visibleAnnotations = send.visibleAnnotations;
    tick.invisibleAnnotations = send.invisibleAnnotations;
    send.visibleAnnotations = null;
    send.invisibleAnnotations = null;
    var tryStart = new LabelNode(), tryEnd = new LabelNode(), handler = new LabelNode();
    tick.visitMethodInsn(O.INVOKESTATIC, BATCH, 'nestworld$takeBatch', '()' + MAP, false);
    tick.visitVarInsn(O.ASTORE, 0);
    tick.instructions.add(tryStart);
    tick.visitVarInsn(O.ALOAD, 0);
    tick.visitMethodInsn(O.INVOKESTATIC, BATCH, 'nestworld$sendBatch', '(' + MAP + ')V', false);
    tick.instructions.add(tryEnd);
    tick.visitInsn(O.RETURN);
    tick.instructions.add(handler);
    tick.instructions.add(new FrameNode(O.F_NEW, 1,
            Java.to(['java/util/Map'], 'java.lang.Object[]'), 1,
            Java.to(['java/lang/Throwable'], 'java.lang.Object[]')));
    tick.visitVarInsn(O.ASTORE, 1);
    tick.visitVarInsn(O.ALOAD, 0);
    tick.visitMethodInsn(O.INVOKESTATIC, BATCH, 'nestworld$restoreBatch', '(' + MAP + ')V', false);
    tick.visitVarInsn(O.ALOAD, 1);
    tick.visitInsn(O.ATHROW);
    tick.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, 'java/lang/Throwable'));
    tick.visitMaxs(1, 2);
    c.methods.add(tick);
}

function removeContainsGuard(method, expectedJump) {
    var nodes = method.instructions.toArray(), match = null;
    for (var i = 0; i < nodes.length; i++) {
        var call = nodes[i];
        if (call.getOpcode() != O.INVOKEINTERFACE || call.owner != 'java/util/Map'
                || call.name != 'containsKey' || call.desc != '(Ljava/lang/Object;)Z') continue;
        var jump = call.getNext();
        while (jump != null && jump.getOpcode() < 0) jump = jump.getNext();
        if (jump == null || jump.getOpcode() != expectedJump)
            throw new Error('Unexpected containsKey branch in ' + method.name);
        var start = call.getPrevious();
        while (start != null && !(start.getOpcode() == O.GETSTATIC && start.owner == BATCH
                && start.name == 'ID_CRYSTAL_MAP' && start.desc == MAP)) start = start.getPrevious();
        if (start == null || match != null) throw new Error('Ambiguous containsKey guard in ' + method.name);
        match = {start: start, end: jump};
    }
    if (match == null) throw new Error('Missing containsKey guard in ' + method.name);
    var cursor = match.start;
    while (cursor != null) {
        var next = cursor.getNext(), done = cursor === match.end;
        method.instructions.remove(cursor);
        if (done) break;
        cursor = next;
    }
}

function patchCrystalTile(c) {
    var load = requireMethod(c, 'onLoad', '()V', false);
    var unload = requireMethod(c, 'onChunkUnloaded', '()V', false);
    var position = null, puts = 0;
    var loadNodes = load.instructions.toArray();
    for (var i = 0; i < loadNodes.length; i++) {
        var node = loadNodes[i];
        if (node.getOpcode() == O.GETFIELD && node.desc == 'Lnet/minecraft/core/BlockPos;') {
            if (position != null) throw new Error('Ambiguous crystal position field');
            position = node;
        }
        if (node.getOpcode() == O.INVOKEINTERFACE && node.owner == 'java/util/Map'
                && node.name == 'put' && node.desc == '(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;') {
            node.name = 'putIfAbsent';
            puts++;
        }
    }
    if (position == null || puts != 1) throw new Error('Unsupported crystal registration shape');
    // containsKey + put is a torn compound operation. putIfAbsent preserves the
    // original first-position-wins behavior even when hash collisions race.
    removeContainsGuard(load, O.IFNE);

    var removes = 0, unloadNodes = unload.instructions.toArray();
    for (var j = 0; j < unloadNodes.length; j++) {
        var remove = unloadNodes[j];
        if (remove.getOpcode() != O.INVOKEINTERFACE || remove.owner != 'java/util/Map'
                || remove.name != 'remove' || remove.desc != '(Ljava/lang/Object;)Ljava/lang/Object;') continue;
        var value = new InsnList();
        value.add(new VarInsnNode(O.ALOAD, 0));
        value.add(new FieldInsnNode(O.GETFIELD, position.owner, position.name, position.desc));
        unload.instructions.insertBefore(remove, value);
        remove.desc = '(Ljava/lang/Object;Ljava/lang/Object;)Z';
        removes++;
    }
    if (removes != 1) throw new Error('Unsupported crystal removal shape');
    unload.maxStack++;
    // remove(key, position) prevents an unloading stale tile from deleting a
    // newer tile that won registration for the same integer hash.
    removeContainsGuard(unload, O.IFEQ);
}

// Snapshot the collection returned at each ARETURN without changing its public
// descriptor. The whole method is synchronized, so the copy is made under the
// same monitor as writers. Caller iteration happens after releasing that monitor.
function snapshotReturn(method) {
    var nodes = method.instructions.toArray();
    var returns = 0;
    for (var i = 0; i < nodes.length; i++) {
        if (nodes[i].getOpcode() != O.ARETURN) continue;
        var copy = new InsnList();
        // stack: collection -> uninitialized, uninitialized, collection
        copy.add(new TypeInsnNode(O.NEW, 'java/util/ArrayList'));
        copy.add(new InsnNode(O.DUP_X1));
        copy.add(new InsnNode(O.SWAP));
        copy.add(new MethodInsnNode(O.INVOKESPECIAL, 'java/util/ArrayList', '<init>', '(Ljava/util/Collection;)V', false));
        method.instructions.insertBefore(nodes[i], copy);
        returns++;
    }
    if (returns != 1) throw new Error('Expected one collection return in ' + method.name);
    method.maxStack += 2;
    sync(method);
}

function patchFluxBase(c) {
    requireField(c, 'mConnectionMap', 'Ljava/util/HashMap;', false);
    sync(requireMethod(c, 'getConnectionByPos', '(Lnet/minecraft/core/GlobalPos;)Lsonar/fluxnetworks/api/device/IFluxDevice;', false));
    snapshotReturn(requireMethod(c, 'getAllConnections', '()Ljava/util/Collection;', false));
    sync(requireMethod(c, 'onDelete', '()V', false));
    sync(requireMethod(c, 'readCustomTag', '(Lnet/minecraft/nbt/CompoundTag;B)V', false));
}

function patchFluxServer(c) {
    requireField(c, 'mToAdd', 'Ljava/util/LinkedList;', false);
    requireField(c, 'mToRemove', 'Ljava/util/LinkedList;', false);
    requireField(c, 'mDevices', '[Ljava/util/ArrayList;', false);
    var device = 'Lsonar/fluxnetworks/common/device/TileFluxDevice;';
    var handle = requireMethod(c, 'handleConnectionQueue', '()V', false);
    var add = requireMethod(c, 'enqueueConnectionAddition', '(' + device + ')Z', false);
    var remove = requireMethod(c, 'enqueueConnectionRemoval', '(' + device + 'Z)V', false);
    sync(handle); sync(add); sync(remove);
    sync(requireMethod(c, 'markSortConnections', '()V', false));
    var deletion = requireMethod(c, 'onDelete', '()V', false);
    var getter = requireMethod(c, 'getLogicalDevices', '(I)Ljava/util/ArrayList;', false);
    // handleConnectionQueue must keep mutating the REAL lists, not their copies.
    // Give only the three guarded internal operations a private live-list getter.
    var raw = new MethodNode(O.ACC_PRIVATE | O.ACC_SYNTHETIC, 'nestworld$getLogicalDevices', getter.desc, null, null);
    getter.accept(raw);
    raw.name = 'nestworld$getLogicalDevices';
    raw.access = O.ACC_PRIVATE | O.ACC_SYNTHETIC;
    c.methods.add(raw);
    var internal = [handle, add, remove];
    for (var i = 0; i < internal.length; i++) {
        var nodes = internal[i].instructions.toArray();
        for (var n = 0; n < nodes.length; n++) {
            var node = nodes[n];
            if (node.getOpcode() == O.INVOKEVIRTUAL && node.owner == SERVER_FLUX
                    && node.name == 'getLogicalDevices' && node.desc == getter.desc)
                node.name = raw.name;
        }
    }
    snapshotReturn(getter);
    patchFluxDeletion(c, deletion, add, remove);
    // onEndServerTick deliberately remains unlocked: it consumes snapshots and
    // calls energy handlers. Never hold a network monitor across those callbacks.

}

function patchFluxDeletion(c, deletion, add, remove) {
    var deleted = 'nestworld$deleted';
    for (var f = 0; f < c.fields.size(); f++) {
        if (c.fields.get(f).name == deleted) throw new Error('Deletion marker already exists');
    }
    c.fields.add(new FieldNode(O.ACC_PRIVATE, deleted, 'Z', null, null));
    // Preserve the original disconnect Consumer, but run it AFTER detaching
    // under the monitor. disconnect() can call game code and other networks.
    var consumer = null, nodes = deletion.instructions.toArray();
    for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        if (n.getOpcode() == O.INVOKEDYNAMIC) {
            if (consumer != null || n.desc != '()Ljava/util/function/Consumer;')
                throw new Error('Unsupported deletion callback');
            consumer = n;
        }
    }
    if (consumer == null) throw new Error('Missing deletion callback');
    var detach = new MethodNode(O.ACC_PRIVATE | O.ACC_SYNCHRONIZED | O.ACC_SYNTHETIC,
            'nestworld$detachDevices', '()Ljava/util/ArrayList;', null, null);
    detach.visitVarInsn(O.ALOAD, 0);
    detach.visitMethodInsn(O.INVOKESPECIAL, FLUX, 'onDelete', '()V', false);
    detach.visitTypeInsn(O.NEW, 'java/util/ArrayList');
    detach.visitInsn(O.DUP);
    detach.visitVarInsn(O.ALOAD, 0);
    detach.visitInsn(O.ICONST_0);
    detach.visitMethodInsn(O.INVOKEVIRTUAL, SERVER_FLUX, 'nestworld$getLogicalDevices', '(I)Ljava/util/ArrayList;', false);
    detach.visitMethodInsn(O.INVOKESPECIAL, 'java/util/ArrayList', '<init>', '(Ljava/util/Collection;)V', false);
    detach.visitVarInsn(O.ASTORE, 1);
    detach.visitVarInsn(O.ALOAD, 0);
    detach.visitInsn(O.ICONST_1);
    detach.visitFieldInsn(O.PUTFIELD, SERVER_FLUX, deleted, 'Z');
    // Keep empty live lists after deletion so stale readers see empty snapshots
    // rather than the original implementation's null list slots.
    detach.visitVarInsn(O.ALOAD, 0);
    detach.visitFieldInsn(O.GETFIELD, SERVER_FLUX, 'mDevices', '[Ljava/util/ArrayList;');
    detach.visitMethodInsn(O.INVOKESTATIC, 'net/nestworld/compat/NestworldCoremodSupport',
            'clearLists', '([Ljava/lang/Object;)V', false);
    var queues = ['mToAdd', 'mToRemove'];
    for (var q = 0; q < queues.length; q++) {
        detach.visitVarInsn(O.ALOAD, 0);
        detach.visitFieldInsn(O.GETFIELD, SERVER_FLUX, queues[q], 'Ljava/util/LinkedList;');
        detach.visitMethodInsn(O.INVOKEVIRTUAL, 'java/util/LinkedList', 'clear', '()V', false);
    }
    detach.visitVarInsn(O.ALOAD, 1);
    detach.visitInsn(O.ARETURN);
    detach.visitMaxs(4, 2);
    c.methods.add(detach);
    // Retain annotations/signature, discard the replaced body and debug locals.
    deletion.instructions.clear();
    deletion.tryCatchBlocks.clear();
    if (deletion.localVariables != null) deletion.localVariables.clear();
    deletion.visibleLocalVariableAnnotations = null;
    deletion.invisibleLocalVariableAnnotations = null;
    deletion.access = deletion.access & ~O.ACC_SYNCHRONIZED;
    deletion.visitVarInsn(O.ALOAD, 0);
    deletion.visitMethodInsn(O.INVOKEVIRTUAL, SERVER_FLUX, detach.name, detach.desc, false);
    deletion.instructions.add(consumer);
    deletion.visitMethodInsn(O.INVOKEVIRTUAL, 'java/util/ArrayList', 'forEach', '(Ljava/util/function/Consumer;)V', false);
    deletion.visitInsn(O.RETURN);
    deletion.visitMaxs(2, 1);
    var guarded = [add, remove];
    for (var g = 0; g < guarded.length; g++) {
        var method = guarded[g], prefix = new InsnList(), live = new LabelNode();
        prefix.add(new VarInsnNode(O.ALOAD, 0));
        prefix.add(new FieldInsnNode(O.GETFIELD, SERVER_FLUX, deleted, 'Z'));
        prefix.add(new JumpInsnNode(O.IFEQ, live));
        if (g == 0) { prefix.add(new InsnNode(O.ICONST_0)); prefix.add(new InsnNode(O.IRETURN)); }
        else prefix.add(new InsnNode(O.RETURN));
        prefix.add(live);
        var locals = [SERVER_FLUX, 'sonar/fluxnetworks/common/device/TileFluxDevice'];
        if (g == 1) locals.push(O.INTEGER);
        prefix.add(new FrameNode(O.F_NEW, locals.length, Java.to(locals, 'java.lang.Object[]'), 0, null));
        method.instructions.insert(prefix);
    }
    var valid = requireMethod(c, 'isValid', '()Z', false);
    valid.instructions.clear(); valid.tryCatchBlocks.clear();
    if (valid.localVariables != null) valid.localVariables.clear();
    valid.visitVarInsn(O.ALOAD, 0);
    valid.visitFieldInsn(O.GETFIELD, SERVER_FLUX, deleted, 'Z');
    valid.visitInsn(O.ICONST_1);
    valid.visitInsn(O.IXOR);
    valid.visitInsn(O.IRETURN);
    valid.visitMaxs(2, 1);
    sync(valid);
}

function transformCompound(input) {
    // Expand frames without resolving Minecraft classes; operate on a new tree.
    var writer = new ClassWriter(0);
    input.accept(writer);
    var c = new ClassNode();
    new ClassReader(writer.toByteArray()).accept(c, ClassReader.EXPAND_FRAMES);
    noHelperCollision(c);
    if (c.name == PATHS) patchPaths(c);
    else if (c.name == RECIPES) patchRecipes(c);
    else if (c.name == BATCH) patchBatch(c);
    else if (c.name == CRYSTAL_TILE) patchCrystalTile(c);
    else if (c.name == FLUX) patchFluxBase(c);
    else if (c.name == SERVER_FLUX) patchFluxServer(c);
    else throw new Error('Unknown target ' + c.name);
    ASMAPI.log('INFO', '[NestWorld] compound-operation compatibility fix applied to ' + c.name);
    return c;
}

function initializeCoreMod() {
    return { 'nestworld_mod_compound_races': {
        'target': { 'type': 'CLASS', 'names': function () { return [PATHS, RECIPES, BATCH, CRYSTAL_TILE, FLUX, SERVER_FLUX]; } },
        'transformer': function (input) {
            try { return transformCompound(input); }
            catch (error) {
                ASMAPI.log('ERROR', '[NestWorld] COMPATIBILITY FIX NOT APPLIED to ' + input.name
                        + ': ' + error + '. Original class retained; this mod/version is not protected.');
                return input;
            }
        }
    } };
}
