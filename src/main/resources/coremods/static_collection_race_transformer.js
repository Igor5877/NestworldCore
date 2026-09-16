// NestWorld: generic, config-driven fix for the recurring "third-party mod holds a plain
// (non-concurrent) static collection, safe under vanilla's single-threaded tick, torn open by
// NestworldCore's region-parallel block-entity/entity ticking" bug class. See project memory
// "exdeorum-visualupdatetracker-race" and "full-modpack-race-sweep-2026-09-09" for the sweep
// that found these -- this transformer exists so each new confirmed hit of THIS SPECIFIC shape
// (a plain collection allocated at a fixed site, safe to swap for a concurrent equivalent
// in-place) is a JSON entry in static_collection_race_sites.json, not a new hand-written
// coremod. Per-mod hardcoded coremods (like the original exdeorum one this replaces) don't
// scale once the same bug shape shows up in a fifth, sixth, seventh mod.
//
// IMPORTANT SCOPE LIMIT (do not add sites here that don't fit): this only handles bugs where
// swapping the ALLOCATION SITE of a collection to a concurrent-safe equivalent is a complete,
// correct fix by itself -- i.e. writer-vs-iterator races on a single collection with no
// cross-field invariant, no compound check-then-act sequence, no ordering/snapshot semantics
// the caller depends on. Confirmed races that need atomic compound operations
// (`computeIfAbsent`-shaped, not just concurrent `put`), producer/consumer queue+atomic-drain
// semantics, immutable-snapshot reads, or main-thread serialization of the actual business
// logic (not just the collection) do NOT belong here -- those need their own bespoke
// transformer or a different fix class entirely. See docs/RACE_CLASSIFICATION.md for the C1-C7
// fix-class taxonomy; this transformer implements C1 (concurrent container swap) ONLY.
//
// Two edit kinds, both pure 1:1 bytecode substitutions with no stack-shape change:
//   "ctor-swap": NEW <from>; DUP; INVOKESPECIAL <from>.<init>()V
//             -> NEW <to>;   DUP; INVOKESPECIAL <to>.<init>()V
//     (same no-arg constructor descriptor on both sides -- e.g. WeakHashMap -> ConcurrentHashMap)
//   "ctor-to-factory": NEW <from>; DUP; INVOKESPECIAL <from>.<init>()V
//                    -> single INVOKESTATIC <toOwner>.<toName> <toDesc>
//     (e.g. new HashSet<>() -> ConcurrentHashMap.newKeySet() -- 3 nodes collapse to 1, same net
//     stack effect: one reference pushed either way)
//
// Fixing the allocation site (not the value passed to a later Map.put()) matters: a mod's own
// bytecode típically does `map.put(key, ref = new HashSet<>())` and keeps using `ref` directly
// afterward -- substituting what the MAP stores after the fact doesn't change what the mod's
// own local variable points at, so that kind of fix is silently inert. Swapping the allocation
// itself means every reference to the object -- the map's stored value AND the mod's own local
// variable -- is the same, already-safe, object from the start.

var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var TypeInsnNode = Java.type('org.objectweb.asm.tree.TypeInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

function loadSites() {
    var raw = ASMAPI.loadData('coremods/static_collection_race_sites.json');
    var byClass = {};
    for (var i = 0; i < raw.length; i++) {
        var entry = raw[i];
        byClass[entry['class']] = entry;
    }
    return byClass;
}

function applyCtorSwap(insns, node, edit) {
    var dup = node.getNext();
    if (dup == null || dup.getOpcode() != Opcodes.DUP) return false;
    var ctor = dup.getNext();
    if (ctor == null || ctor.getOpcode() != Opcodes.INVOKESPECIAL) return false;
    if (!ctor.owner.equals(edit.from) || !ctor.name.equals('<init>') || !ctor.desc.equals('()V')) return false;
    insns.set(node, new TypeInsnNode(Opcodes.NEW, edit.to));
    insns.set(ctor, new MethodInsnNode(Opcodes.INVOKESPECIAL, edit.to, '<init>', '()V', false));
    return true;
}

function applyCtorToFactory(insns, node, edit) {
    var dup = node.getNext();
    if (dup == null || dup.getOpcode() != Opcodes.DUP) return false;
    var ctor = dup.getNext();
    if (ctor == null || ctor.getOpcode() != Opcodes.INVOKESPECIAL) return false;
    if (!ctor.owner.equals(edit.from) || !ctor.name.equals('<init>') || !ctor.desc.equals('()V')) return false;
    var factoryCall = new MethodInsnNode(Opcodes.INVOKESTATIC, edit.toOwner, edit.toName, edit.toDesc, false);
    insns.insertBefore(node, factoryCall);
    insns.remove(node);
    insns.remove(dup);
    insns.remove(ctor);
    return true;
}

function applyEditsInMethod(method, edits) {
    var insns = method.instructions;
    var appliedCount = 0;
    for (var e = 0; e < edits.length; e++) {
        var edit = edits[e];
        for (var i = 0; i < insns.size(); i++) {
            var node = insns.get(i);
            if (node.getOpcode() != Opcodes.NEW || !node.desc.equals(edit.from)) continue;
            var ok;
            if (edit.kind == 'ctor-swap') ok = applyCtorSwap(insns, node, edit);
            else if (edit.kind == 'ctor-to-factory') ok = applyCtorToFactory(insns, node, edit);
            else ok = false;
            if (ok) { appliedCount++; break; } // one allocation site per edit entry, by design
        }
    }
    return appliedCount;
}

function initializeCoreMod() {
    var sites = loadSites();
    var classNames = Object.keys(sites);

    return {
        'nestworld_static_collection_race_transformer': {
            'target': {
                'type': 'CLASS',
                'names': function (listOfClasses) {
                    return classNames;
                }
            },
            'transformer': function (classNode) {
                var site = sites[classNode.name];
                if (site == null) return classNode; // shouldn't happen, target list is exact
                var totalApplied = 0;
                for (var m = 0; m < classNode.methods.size(); m++) {
                    var method = classNode.methods.get(m);
                    var edits = [];
                    for (var e = 0; e < site.edits.length; e++) {
                        if (site.edits[e]['in'] == method.name) edits.push(site.edits[e]);
                    }
                    if (edits.length > 0) totalApplied += applyEditsInMethod(method, edits);
                }
                ASMAPI.log('INFO', '[NestWorld] static-collection race fix applied to ' + classNode.name
                        + ' (' + site.mod + '): ' + totalApplied + '/' + site.edits.length
                        + ' allocation site(s) patched');
                return classNode;
            }
        }
    };
}
