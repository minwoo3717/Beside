using System;
using System.IO;
using System.Linq;
using System.Text;
using UnityEngine;

namespace Beside.FurLab
{
    /// <summary>
    /// Sends FurBenchmark results off the phone without a cable (Android share sheet: KakaoTalk, mail, Drive...).
    ///  - Default: the CSV is shared as text (works everywhere, no plugin). Paste it into a .csv file on the PC.
    ///  - With NativeShare (yasirkula, same author as NativeGallery) installed and the scripting define
    ///    BESIDE_NATIVESHARE set, the CSV is shared as a real file attachment.
    /// </summary>
    public static class FurResultShare
    {
        public static string Folder => Path.Combine(Application.persistentDataPath, "fur_bench");

        public static string[] CsvFiles()
        {
            if (!Directory.Exists(Folder)) return Array.Empty<string>();
            return Directory.GetFiles(Folder, "fur_bench_*.csv")
                .Where(p => !p.EndsWith("_all.csv") && !p.EndsWith("_summary.csv"))
                .OrderBy(p => p).ToArray();
        }

        public static bool HasResults => CsvFiles().Length > 0;

        public static string Latest()
        {
            var f = CsvFiles();
            return f.Length > 0 ? f[f.Length - 1] : null;
        }

        /// <summary>All runs in one CSV (one header). Returns its path.</summary>
        public static string MergeAll()
        {
            var files = CsvFiles();
            if (files.Length == 0) return null;
            var sb = new StringBuilder();
            bool header = false;
            foreach (var f in files)
            {
                var lines = File.ReadAllLines(f);
                for (int i = 0; i < lines.Length; i++)
                {
                    if (string.IsNullOrWhiteSpace(lines[i])) continue;
                    if (i == 0) { if (header) continue; header = true; }
                    sb.Append(lines[i]).Append('\n');
                }
            }
            string dst = Path.Combine(Folder, "fur_bench_all.csv");
            File.WriteAllText(dst, sb.ToString());
            return dst;
        }

        /// <summary>Opens the share sheet for this CSV. Returns a short message for the panel.</summary>
        public static string Share(string csvPath, string summary)
        {
            if (string.IsNullOrEmpty(csvPath) || !File.Exists(csvPath)) return "공유할 결과가 없습니다.";
            string name = Path.GetFileName(csvPath);
            string subject = $"Beside 털 측정 {name} ({SystemInfo.deviceModel})";
#if BESIDE_NATIVESHARE
            new NativeShare().AddFile(csvPath).SetSubject(subject).SetText(summary ?? "").SetTitle("측정 결과 공유").Share();
            return "파일로 공유합니다.";
#else
            string text = (string.IsNullOrEmpty(summary) ? "" : summary + "\n\n")
                        + $"# {name}  (아래를 {name} 로 저장)\n" + File.ReadAllText(csvPath);
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                var activity = UnityEngine.Android.AndroidApplication.currentActivity;
                using var intent = new AndroidJavaObject("android.content.Intent");
                intent.Call<AndroidJavaObject>("setAction", "android.intent.action.SEND");
                intent.Call<AndroidJavaObject>("setType", "text/plain");
                intent.Call<AndroidJavaObject>("putExtra", "android.intent.extra.SUBJECT", subject);
                intent.Call<AndroidJavaObject>("putExtra", "android.intent.extra.TEXT", text);
                using var intentClass = new AndroidJavaClass("android.content.Intent");
                using var chooser = intentClass.CallStatic<AndroidJavaObject>("createChooser", intent, "측정 결과 공유");
                activity.Call("startActivity", chooser);
                return "텍스트로 공유합니다. PC에서 .csv 로 저장하세요.";
            }
            catch (Exception e)
            {
                Debug.LogWarning("[FurResultShare] share failed: " + e.Message);
                GUIUtility.systemCopyBuffer = text;
                return "공유 창을 열지 못해 클립보드에 복사했습니다.";
            }
#else
            GUIUtility.systemCopyBuffer = text;
            Application.OpenURL("file://" + Folder);
            return $"클립보드에 복사하고 폴더를 열었습니다: {Folder}";
#endif
#endif
        }

        public static string Copy(string csvPath)
        {
            if (string.IsNullOrEmpty(csvPath) || !File.Exists(csvPath)) return "복사할 결과가 없습니다.";
            GUIUtility.systemCopyBuffer = File.ReadAllText(csvPath);
            return "CSV를 클립보드에 복사했습니다.";
        }
    }
}
