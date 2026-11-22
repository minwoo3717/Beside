using System.Collections.Generic;
using UnityEngine;

namespace Beside.FurLab
{
    /// <summary>
    /// Shares the current pose mesh between all fur renderers on one model, so shell + fins + strands
    /// bake a skinned mesh only once per frame. Also hands the GPU vertex buffer to the strand/fin shaders,
    /// which read root positions straight from it (no CPU work per strand).
    /// </summary>
    public static class FurPose
    {
        public struct Layout
        {
            public int stride, posOffset, normalOffset, tangentOffset;  // tangentOffset -1 = none
            public bool valid;
        }

        class Entry
        {
            public Mesh baked;               // null for static meshes
            public int bakedFrame = -1;
            public GraphicsBuffer buffer;    // wrapper of the mesh's vertex buffer, renewed each frame
            public int bufferFrame = -1;
            public Layout layout;
        }

        static readonly Dictionary<Renderer, Entry> entries = new Dictionary<Renderer, Entry>();

        /// <summary>Mesh in the renderer's local space for this frame (skinned: baked once per frame).</summary>
        public static Mesh GetMesh(Renderer r, bool useScale = false)
        {
            if (r == null) return null;
            if (r is SkinnedMeshRenderer smr)
            {
                var e = GetEntry(r);
                if (e.baked == null)
                {
                    e.baked = new Mesh { name = "FurPoseBaked" };
                    e.baked.vertexBufferTarget |= GraphicsBuffer.Target.Raw;
                }
                if (e.bakedFrame != Time.frameCount)
                {
                    smr.BakeMesh(e.baked, useScale);
                    e.bakedFrame = Time.frameCount;
                }
                return e.baked;
            }
            var mf = r.GetComponent<MeshFilter>();
            return mf != null ? mf.sharedMesh : null;
        }

        /// <summary>Raw vertex buffer (stream holding positions) of this frame's mesh, plus where pos/normal/tangent sit in it.</summary>
        public static GraphicsBuffer GetVertexBuffer(Renderer r, bool useScale, out Layout layout)
        {
            layout = default;
            Mesh mesh = GetMesh(r, useScale);
            if (mesh == null) return null;
            var e = GetEntry(r);
            if (e.bufferFrame == Time.frameCount && e.buffer != null) { layout = e.layout; return e.buffer; }

            if ((mesh.vertexBufferTarget & GraphicsBuffer.Target.Raw) == 0)
                mesh.vertexBufferTarget |= GraphicsBuffer.Target.Raw;   // static mesh: enable once

            int stream = mesh.GetVertexAttributeStream(UnityEngine.Rendering.VertexAttribute.Position);
            var l = new Layout
            {
                stride = mesh.GetVertexBufferStride(stream),
                posOffset = mesh.GetVertexAttributeOffset(UnityEngine.Rendering.VertexAttribute.Position),
                normalOffset = SameStream(mesh, UnityEngine.Rendering.VertexAttribute.Normal, stream),
                tangentOffset = SameStream(mesh, UnityEngine.Rendering.VertexAttribute.Tangent, stream),
            };
            l.valid = l.normalOffset >= 0
                && mesh.GetVertexAttributeFormat(UnityEngine.Rendering.VertexAttribute.Position) == UnityEngine.Rendering.VertexAttributeFormat.Float32
                && mesh.GetVertexAttributeFormat(UnityEngine.Rendering.VertexAttribute.Normal) == UnityEngine.Rendering.VertexAttributeFormat.Float32;
            if (l.tangentOffset >= 0 && mesh.GetVertexAttributeFormat(UnityEngine.Rendering.VertexAttribute.Tangent) != UnityEngine.Rendering.VertexAttributeFormat.Float32)
                l.tangentOffset = -1;
            if (!l.valid) Debug.LogWarning("[FurPose] vertex layout not supported (need float32 position+normal in one stream)");

            e.buffer?.Dispose();             // previous frame's wrapper (its draw is finished)
            e.buffer = mesh.GetVertexBuffer(stream);
            e.bufferFrame = Time.frameCount;
            e.layout = l;
            layout = l;
            return e.buffer;
        }

        /// <summary>
        /// Matrix to draw this frame's mesh with, and how many mesh units one object unit is.
        /// BakeMesh(useScale: false) leaves the renderer's scale already applied to the vertices (world-size mesh),
        /// so drawing it with localToWorldMatrix would scale it twice — the fur came out ~0.36x, between the legs.
        /// </summary>
        public static Matrix4x4 DrawMatrix(Renderer r, bool useScale, out float unitScale)
        {
            Transform t = r.transform;
            if (r is SkinnedMeshRenderer && !useScale)
            {
                unitScale = t.lossyScale.x;   // object units -> baked (world-size) units; uniform scale assumed
                return Matrix4x4.TRS(t.position, t.rotation, Vector3.one);
            }
            unitScale = 1f;
            return t.localToWorldMatrix;
        }

        public static void Release(Renderer r)
        {
            if (r == null || !entries.TryGetValue(r, out var e)) return;
            e.buffer?.Dispose();
            if (e.baked != null) Object.Destroy(e.baked);
            entries.Remove(r);
        }

        static int SameStream(Mesh m, UnityEngine.Rendering.VertexAttribute a, int stream) =>
            m.HasVertexAttribute(a) && m.GetVertexAttributeStream(a) == stream ? m.GetVertexAttributeOffset(a) : -1;

        static Entry GetEntry(Renderer r)
        {
            if (!entries.TryGetValue(r, out var e))
            {
                if (entries.Count > 16) Prune();
                e = new Entry();
                entries[r] = e;
            }
            return e;
        }

        static void Prune()
        {
            var dead = new List<Renderer>();
            foreach (var kv in entries) if (kv.Key == null) dead.Add(kv.Key);
            foreach (var k in dead) { entries[k].buffer?.Dispose(); if (entries[k].baked != null) Object.Destroy(entries[k].baked); entries.Remove(k); }
        }
    }

    /// <summary>
    /// Benchmark-only load multiplier: every fur renderer (and PetLoadRepeater for the base mesh) draws its work
    /// this many times per frame. With enough repeats the GPU is the bottleneck for every condition, runs at full
    /// clock, and frame time / Repeat = what one pet really costs. 1 = normal app.
    /// </summary>
    public static class FurLoad
    {
        public static int Repeat = 1;
    }

    /// <summary>The same spring as ShellFurRenderer: fur lags behind movement and swings back.</summary>
    public class FurSpring
    {
        public float inertia = 15f, stiffness = 60f, damping = 6f, maxBend = 0.8f;
        Vector3 last, offset, vel; bool has;

        /// <summary>Returns the bend in the object's local space.</summary>
        public Vector3 Step(Transform t, bool enabled)
        {
            float dt = Time.deltaTime;
            if (!Application.isPlaying || !enabled || dt <= 0f) { offset = vel = Vector3.zero; has = false; return Vector3.zero; }
            Vector3 p = t.position;
            if (!has) { last = p; has = true; }
            Vector3 d = p - last; last = p;
            if (d.magnitude < 0.5f) offset -= d * inertia;
            vel += -offset * stiffness * dt;
            vel *= 1f / (1f + damping * dt);
            offset += vel * dt;
            offset = Vector3.ClampMagnitude(offset, maxBend);
            return t.InverseTransformDirection(offset);
        }
    }
}
