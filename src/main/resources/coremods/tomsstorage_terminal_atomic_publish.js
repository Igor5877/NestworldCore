// NestWorld: toms_storage StorageTerminalBlockEntity.items -- C3 fix (Consistent Snapshot /
// Atomic Publication). See docs/RACE_CLASSIFICATION.md's toms_storage entry (reclassified
// 2026-09-10 from an earlier C4 guess) and project memory
// "tomsstorage-c4-to-c3-reclassified-2026-09-10" for the full writer/reader contract this fix is
// based on.
//
// This is deliberately NOT added to static_collection_race_transformer.js's config: that engine
// is scoped to C1 (a bare concurrent-container swap at one allocation site) and this is a
// different shape -- the writer (updateServer(), region thread) must build a full replacement
// map WITHOUT any reader (TerminalSyncManager.update(), main thread, via getStacks()) ever
// observing a partially-rebuilt state. A container swap alone doesn't provide that; this needs
// build-into-a-private-field-then-atomic-publish.
//
// Confirmed via independent javap disassembly of the real toms_storage-1.20-1.7.1.jar (own pass,
// not guessed) against
// com/tom/storagemod/tile/StorageTerminalBlockEntity.class:
//   - `items` field: plain (non-volatile) `Map`, initialized to `new HashMap<>()` in both
//     constructors.
//   - `updateServer()`: `this.items.clear();` (ALOAD 0; GETFIELD items; INVOKEINTERFACE
//     Map.clear()V) unconditionally, then (if a connected inventory exists) a Stream.forEach
//     over a Consumer created via `aload_0; invokedynamic ...accept:(LStorageTerminalBlockEntity;
//     )Ljava/util/function/Consumer;` whose target is the synthetic instance method
//     `lambda$updateServer$2(StoredItemStack)`, which does `aload_0; getfield items; ...
//     invokeinterface Map.merge(...)`. Both branches (connected inventory found or not) converge
//     at a single `aload_0; iconst_0; putfield updateItems` (`this.updateItems = false;`).
//   - `TerminalSyncManager.update()` (the reader, main thread) does `items.forEach(...)`
//     directly over whatever `getStacks()` returned -- `return this.items;`, no defensive copy.
//
// The fix, expressed as three bytecode edits on ONE class plus a field-shape change:
//   1. Add a new private field `items_building` (same descriptor as `items`, no volatile needed
//      -- only ever touched by the BE's own owning thread).
//   2. Flip `items`'s access flags to add ACC_VOLATILE.
//   3. In `updateServer()`: replace `this.items.clear();` with
//      `this.items_building = new HashMap();` (NEW/DUP/INVOKESPECIAL/PUTFIELD, same net stack
//      effect: nothing left on stack either way).
//   4. In `updateServer()`: insert `this.items = this.items_building;` (ALOAD 0; ALOAD 0;
//      GETFIELD items_building; PUTFIELD items) immediately before the existing
//      `this.updateItems = false;`, so the branch that finds no connected inventory (which still
//      needs `items` to end up empty, matching original behavior) also gets a real publish, not
//      a stale reference.
//   5. In `lambda$updateServer$2`: change its `GETFIELD items` to `GETFIELD items_building`, so
//      every merge() call during the rebuild writes into the private, not-yet-published map --
//      readers can never observe a partially-rebuilt HashMap.
//
// Net effect: `getStacks()` (unchanged) always returns either a fully-old or fully-new `items`
// reference via one `volatile` read/write pair -- never a torn one. No cross-region dispatch
// needed; self-contained inside this one class, matching the "no same-tick causal-ordering
// contract" conclusion in the race-classification entry.

var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var FieldNode = Java.type('org.objectweb.asm.tree.FieldNode');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var TypeInsnNode = Java.type('org.objectweb.asm.tree.TypeInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');

var TARGET_CLASS = 'com/tom/storagemod/tile/StorageTerminalBlockEntity';
var UPDATE_SERVER = 'updateServer';
var MERGE_LAMBDA = 'lambda$updateServer$2';

function addVolatileAndBuildingField(classNode) {
    var itemsField = null;
    for (var i = 0; i < classNode.fields.size(); i++) {
        var f = classNode.fields.get(i);
        if (f.name.equals('items')) { itemsField = f; break; }
    }
    if (itemsField == null) return false;
    itemsField.access = itemsField.access | Opcodes.ACC_VOLATILE;
    var buildingField = new FieldNode(Opcodes.ACC_PRIVATE, 'items_building', itemsField.desc, itemsField.signature, null);
    classNode.fields.add(buildingField);
    return true;
}

// this.items.clear();  ->  this.items_building = new HashMap();
function patchClearToLocalBuild(method) {
    var insns = method.instructions;
    var nodes = insns.toArray();
    for (var i = 0; i + 2 < nodes.length; i++) {
        var n0 = nodes[i], n1 = nodes[i + 1], n2 = nodes[i + 2];
        if (n0.getOpcode() != Opcodes.ALOAD || n0.var != 0) continue;
        if (n1.getOpcode() != Opcodes.GETFIELD) continue;
        if (!n1.owner.equals(TARGET_CLASS) || !n1.name.equals('items')) continue;
        if (n2.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
        if (!n2.owner.equals('java/util/Map') || !n2.name.equals('clear')) continue;

        var repl = new InsnList();
        repl.add(new VarInsnNode(Opcodes.ALOAD, 0));
        repl.add(new TypeInsnNode(Opcodes.NEW, 'java/util/HashMap'));
        repl.add(new InsnNode(Opcodes.DUP));
        repl.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, 'java/util/HashMap', '<init>', '()V', false));
        repl.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS, 'items_building', n1.desc));

        insns.insertBefore(n0, repl);
        insns.remove(n0);
        insns.remove(n1);
        insns.remove(n2);
        return true;
    }
    return false;
}

// insert `this.items = this.items_building;` right before the existing `this.updateItems = false;`
function patchAtomicPublish(method) {
    var insns = method.instructions;
    var nodes = insns.toArray();
    for (var i = 0; i + 2 < nodes.length; i++) {
        var n0 = nodes[i], n1 = nodes[i + 1], n2 = nodes[i + 2];
        if (n0.getOpcode() != Opcodes.ALOAD || n0.var != 0) continue;
        if (n1.getOpcode() != Opcodes.ICONST_0) continue;
        if (n2.getOpcode() != Opcodes.PUTFIELD) continue;
        if (!n2.owner.equals(TARGET_CLASS) || !n2.name.equals('updateItems')) continue;

        var repl = new InsnList();
        repl.add(new VarInsnNode(Opcodes.ALOAD, 0));
        repl.add(new VarInsnNode(Opcodes.ALOAD, 0));
        repl.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, 'items_building', 'Ljava/util/Map;'));
        repl.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS, 'items', 'Ljava/util/Map;'));

        insns.insertBefore(n0, repl);
        return true;
    }
    return false;
}

// lambda$updateServer$2: GETFIELD items -> GETFIELD items_building
function patchLambdaField(method) {
    var insns = method.instructions;
    for (var i = 0; i < insns.size(); i++) {
        var n = insns.get(i);
        if (n.getOpcode() == Opcodes.GETFIELD && n.owner.equals(TARGET_CLASS) && n.name.equals('items')) {
            n.name = 'items_building';
            return true;
        }
    }
    return false;
}

function initializeCoreMod() {
    return {
        'nestworld_tomsstorage_atomic_publish': {
            'target': {
                'type': 'CLASS',
                'names': function (listOfClasses) {
                    return [TARGET_CLASS];
                }
            },
            'transformer': function (classNode) {
                var applied = 0;
                if (addVolatileAndBuildingField(classNode)) applied++;

                var updateServerMethod = null, lambdaMethod = null;
                for (var m = 0; m < classNode.methods.size(); m++) {
                    var method = classNode.methods.get(m);
                    if (method.name.equals(UPDATE_SERVER)) updateServerMethod = method;
                    else if (method.name.equals(MERGE_LAMBDA)) lambdaMethod = method;
                }

                if (updateServerMethod != null) {
                    if (patchClearToLocalBuild(updateServerMethod)) applied++;
                    if (patchAtomicPublish(updateServerMethod)) applied++;
                } else {
                    ASMAPI.log('ERROR', '[NestWorld] toms_storage atomic-publish fix: '
                            + UPDATE_SERVER + ' not found in ' + classNode.name);
                }

                if (lambdaMethod != null) {
                    if (patchLambdaField(lambdaMethod)) applied++;
                } else {
                    ASMAPI.log('ERROR', '[NestWorld] toms_storage atomic-publish fix: '
                            + MERGE_LAMBDA + ' not found in ' + classNode.name);
                }

                ASMAPI.log('INFO', '[NestWorld] toms_storage atomic-publish fix applied to '
                        + classNode.name + ': ' + applied + '/4 edit(s) patched');
                return classNode;
            }
        }
    };
}
