using System;
using System.Collections.Generic;
using System.IO;
using UnityEngine;

namespace Beside.Library
{
    /// <summary>
    /// Models kept on the phone (at most <see cref="MaxItems"/>): the GLB, the job's photo and what the user wrote about
    /// the pet (name, height, memo). Files live in persistentDataPath/library: index.json, {id}.glb, {id}.jpg. The GLB is
    /// a copy, not a link to JobManager's download cache, so starting over or a new server never loses a saved model.
    /// The id is the job id the model came from.
    /// </summary>
    public static class ModelLibrary
    {
        public const int MaxItems = 5;

        [Serializable]
        public class Entry
        {
            public string id;            // job id
            public string name;
            public int heightCm;         // height the user gave; 0 = the size the app fitted the model to
            public int autoHeightCm;     // that fitted size (shown when heightCm is 0)
            public string memo;
            public long createdUnixMs;

            public int ShownHeightCm => heightCm > 0 ? heightCm : autoHeightCm;
            public DateTime CreatedLocal => DateTimeOffset.FromUnixTimeMilliseconds(createdUnixMs).LocalDateTime;
        }

        [Serializable]
        class Index { public List<Entry> items = new List<Entry>(); }

        static Index index;

        static string Dir => Path.Combine(Application.persistentDataPath, "library");
        static string IndexPath => Path.Combine(Dir, "index.json");

        /// <summary>Saved models, oldest first. Edit an entry's fields, then call <see cref="SaveChanges"/>.</summary>
        public static IReadOnlyList<Entry> Items { get { Load(); return index.items; } }
        public static int Count => Items.Count;
        public static bool IsFull => Count >= MaxItems;

        public static string GlbPath(Entry e) => Path.Combine(Dir, Safe(e.id) + ".glb");
        public static string PhotoPath(Entry e) => Path.Combine(Dir, Safe(e.id) + ".jpg");

        public static Entry Find(string id)
        {
            if (string.IsNullOrEmpty(id)) return null;
            foreach (var e in Items) if (e.id == id) return e;
            return null;
        }

        /// <summary>Copies the job's GLB (and photo) into the library. Returns the existing entry if the job is already
        /// saved, null when the library is full or the copy failed.</summary>
        public static Entry Add(string id, string glbSource, byte[] photoJpg, string name, int autoHeightCm)
        {
            Load();
            if (string.IsNullOrEmpty(id) || string.IsNullOrEmpty(glbSource) || !File.Exists(glbSource)) return null;
            var existing = Find(id);
            if (existing != null) return existing;
            if (IsFull) return null;

            var e = new Entry
            {
                id = id, name = name, autoHeightCm = autoHeightCm, memo = "",
                createdUnixMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            };
            try
            {
                Directory.CreateDirectory(Dir);
                File.Copy(glbSource, GlbPath(e), true);
                if (photoJpg != null && photoJpg.Length > 0) File.WriteAllBytes(PhotoPath(e), photoJpg);
            }
            catch (Exception ex)
            {
                Debug.LogWarning("[ModelLibrary] could not save the model: " + ex.Message);
                DeleteFiles(e);
                return null;
            }
            index.items.Add(e);
            Save();
            Debug.Log($"[ModelLibrary] saved {e.id} as \"{e.name}\" ({index.items.Count}/{MaxItems})");
            return e;
        }

        public static void Remove(Entry e)
        {
            if (e == null) return;
            Load();
            index.items.RemoveAll(x => x.id == e.id);
            DeleteFiles(e);
            Save();
            Debug.Log($"[ModelLibrary] removed {e.id} ({index.items.Count}/{MaxItems})");
        }

        /// <summary>Writes the index after an entry's fields were changed.</summary>
        public static void SaveChanges() { Load(); Save(); }

        /// <summary>The saved photo as a readable texture (the caller destroys it), or null.</summary>
        public static Texture2D LoadPhoto(Entry e)
        {
            try
            {
                string path = PhotoPath(e);
                if (!File.Exists(path)) return null;
                var tex = new Texture2D(2, 2, TextureFormat.RGBA32, false);
                if (tex.LoadImage(File.ReadAllBytes(path))) { tex.wrapMode = TextureWrapMode.Clamp; return tex; }
                UnityEngine.Object.Destroy(tex);
            }
            catch (Exception ex) { Debug.LogWarning("[ModelLibrary] could not read the photo: " + ex.Message); }
            return null;
        }

        static void Load()
        {
            if (index != null) return;
            index = new Index();
            try
            {
                if (File.Exists(IndexPath)) index = JsonUtility.FromJson<Index>(File.ReadAllText(IndexPath)) ?? new Index();
            }
            catch (Exception ex)
            {
                Debug.LogWarning("[ModelLibrary] index unreadable, starting empty: " + ex.Message);
                index = new Index();
            }
            if (index.items == null) index.items = new List<Entry>();
            // an entry whose model file is gone cannot be opened
            int before = index.items.Count;
            index.items.RemoveAll(x => x == null || string.IsNullOrEmpty(x.id) || !File.Exists(GlbPath(x)));
            if (index.items.Count != before) Save();
        }

        static void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                string tmp = IndexPath + ".tmp";
                File.WriteAllText(tmp, JsonUtility.ToJson(index, true));
                if (File.Exists(IndexPath)) File.Delete(IndexPath);
                File.Move(tmp, IndexPath);
            }
            catch (Exception ex) { Debug.LogWarning("[ModelLibrary] could not write the index: " + ex.Message); }
        }

        static void DeleteFiles(Entry e)
        {
            foreach (string p in new[] { GlbPath(e), PhotoPath(e) })
                try { if (File.Exists(p)) File.Delete(p); } catch (Exception ex) { Debug.LogWarning("[ModelLibrary] " + ex.Message); }
        }

        static string Safe(string id)
        {
            foreach (char c in Path.GetInvalidFileNameChars()) id = id.Replace(c, '_');
            return id;
        }
    }
}
