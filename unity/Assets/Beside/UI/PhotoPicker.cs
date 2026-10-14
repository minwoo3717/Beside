using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using Beside.Api;
using UnityEngine;

namespace Beside.UI
{
    /// <summary>
    /// Supplies photos for a job.
    ///  - Device: NativeGallery (yasirkula) multi-select, each image resized to maxDimension and re-encoded as JPEG
    ///    so no photo exceeds the 5 MB limit. Requires the plugin + scripting define BESIDE_NATIVEGALLERY.
    ///  - Editor: files from editorTestImagePaths.
    ///  - Fallback (no plugin / no paths): a screenshot of the AR camera.
    /// </summary>
    public class PhotoPicker : MonoBehaviour
    {
        [Tooltip("Absolute paths of test photos used in the editor (jpg/png/webp).")]
        public string[] editorTestImagePaths = new string[0];

        [Tooltip("Longest side after resizing photos picked from the gallery.")]
        public int maxDimension = 1600;

        [Range(50, 100)] public int jpegQuality = 85;

        public bool GalleryAvailable
        {
            get
            {
#if BESIDE_NATIVEGALLERY && (UNITY_ANDROID || UNITY_IOS) && !UNITY_EDITOR
                return true;
#else
                return false;
#endif
            }
        }

        /// <summary>Opens the gallery (device) or uses the editor paths; onPicked gets 0 photos when the user cancels.</summary>
        public void Pick(Action<List<Photo>> onPicked)
        {
#if BESIDE_NATIVEGALLERY && (UNITY_ANDROID || UNITY_IOS) && !UNITY_EDITOR
            if (NativeGallery.IsMediaPickerBusy()) return;
            NativeGallery.GetImagesFromGallery(paths =>
            {
                var photos = new List<Photo>();
                if (paths != null)
                {
                    foreach (string path in paths)
                    {
                        if (photos.Count >= ApiV1Routes.MaxPhotos) break;
                        Texture2D tex = NativeGallery.LoadImageAtPath(path, maxDimension, false);
                        if (tex == null) { Debug.LogWarning($"[PhotoPicker] could not load {path}"); continue; }
                        photos.Add(new Photo { bytes = tex.EncodeToJPG(jpegQuality), fileName = Path.GetFileNameWithoutExtension(path) + ".jpg", mimeType = "image/jpeg" });
                        Destroy(tex);
                    }
                }
                onPicked(photos);
            }, "사진 선택", "image/*");
#else
            var photos = new List<Photo>();
            foreach (string p in editorTestImagePaths)
            {
                if (string.IsNullOrEmpty(p)) continue;
                if (!File.Exists(p)) { Debug.LogWarning($"[PhotoPicker] test image not found: {p}"); continue; }
                photos.Add(new Photo { bytes = File.ReadAllBytes(p), fileName = Path.GetFileName(p), mimeType = Photo.MimeFromFileName(p) });
                if (photos.Count >= ApiV1Routes.MaxPhotos) break;
            }
            onPicked(photos);
#endif
        }

        /// <summary>Uses the current camera frame as the photo (test / fallback path).</summary>
        public void CaptureScreen(string fileName, Action<Photo> onCaptured) => StartCoroutine(CaptureRoutine(fileName, onCaptured));

        IEnumerator CaptureRoutine(string fileName, Action<Photo> onCaptured)
        {
            yield return new WaitForEndOfFrame();
            Texture2D shot = ScreenCapture.CaptureScreenshotAsTexture();
            var photo = new Photo { bytes = shot.EncodeToJPG(jpegQuality), fileName = fileName, mimeType = "image/jpeg" };
            Destroy(shot);
            onCaptured(photo);
        }
    }
}
