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
// ANOTHER HARD REQUIREMENT, found the hard way 2026-09-23 (draconicevolution DEEventHandler
// attempt -- VerifyError, whole modpack failed to boot): the FIELD's declared descriptor must
// already be an interface (e.g. `Map`, `Set`) or a common supertype of both <from> and <to>, not
// the concrete <from> class itself. A ctor-swap only changes what gets NEW'd and PUTSTATIC'd; it
// does not (and cannot, this is pure bytecode substitution, no field-descriptor rewriting) touch
// the field's own declared type in the class file. `putstatic`/`putfield` into a field declared
// as `Ljava/util/WeakHashMap;` with a `ConcurrentHashMap` on the stack is bytecode-illegal --
// they're unrelated sibling classes, not sub/supertype -- and the JVM verifier rejects the whole
// class at load time (fails BEFORE any code runs, not a runtime exception). Always confirm the
// field's declared type via `javap -p` (not just the collection interface it happens to satisfy)
// before adding a site here; if it's a concrete class, this transformer cannot fix it as-is.
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

// Resolve every site before touching the input. Descriptors and (where needed) the
// destination field distinguish overloads and repeated allocations of the same type.
function resolveEdit(classNode, edit) {
    var matches = [];
    for (var m = 0; m < classNode.methods.size(); m++) {
        var method = classNode.methods.get(m);
        if (method.name != edit['in'] || method.desc != edit.methodDesc) continue;
        var insns = method.instructions;
        for (var i = 0; i < insns.size(); i++) {
            var node = insns.get(i);
            if (node.getOpcode() != Opcodes.NEW || node.desc != edit.from) continue;
            var dup = node.getNext(), ctor = dup == null ? null : dup.getNext();
            if (dup == null || dup.getOpcode() != Opcodes.DUP || ctor == null
                    || ctor.getOpcode() != Opcodes.INVOKESPECIAL || ctor.owner != edit.from
                    || ctor.name != '<init>' || ctor.desc != '()V') continue;
            if (edit.field != null) {
                var store = ctor.getNext();
                if (store == null || store.getOpcode() != Opcodes.PUTSTATIC
                        || store.owner != classNode.name || store.name != edit.field
                        || store.desc != edit.fieldDesc) continue;
            }
            matches.push({insns: insns, node: node, edit: edit});
        }
    }
    if (matches.length != 1) throw new Error('Expected exactly one allocation for '
            + edit['in'] + edit.methodDesc + ', found ' + matches.length);
    if (edit.kind != 'ctor-swap' && edit.kind != 'ctor-to-factory')
        throw new Error('Unknown edit kind: ' + edit.kind);
    return matches[0];
}

function transformCollections(classNode, site) {
    var resolved = [];
    for (var e = 0; e < site.edits.length; e++) {
        var match = resolveEdit(classNode, site.edits[e]);
        for (var j = 0; j < resolved.length; j++) {
            if (resolved[j].node === match.node) throw new Error('Overlapping allocation edits');
        }
        resolved.push(match);
    }
    for (var i = 0; i < resolved.length; i++) {
        var r = resolved[i];
        var ok = r.edit.kind == 'ctor-swap'
                ? applyCtorSwap(r.insns, r.node, r.edit)
                : applyCtorToFactory(r.insns, r.node, r.edit);
        if (!ok) throw new Error('Allocation changed after preflight');
    }
    ASMAPI.log('INFO', '[NestWorld] static-collection race fix applied to ' + classNode.name
            + ' (' + site.mod + '): ' + resolved.length + '/' + site.edits.length
            + ' allocation site(s) patched');
    return classNode;
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
                try {
                    return transformCollections(classNode, site);
                } catch (error) {
                    ASMAPI.log('ERROR', '[NestWorld] COMPATIBILITY FIX NOT APPLIED to '
                            + classNode.name + ' (' + site.mod + '): ' + error
                            + '. Original class retained; this mod/version is not protected.');
                    return classNode;
                }
            }
        }
    };
}
