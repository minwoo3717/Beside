using System;
using System.Collections;
using UnityEngine;
using UnityEngine.XR.ARFoundation;
#if UNITY_ANDROID && !UNITY_EDITOR
using UnityEngine.Android;
#endif

namespace Beside.AR
{
    /// <summary>
    /// Watches the things that block AR use and tells the UI what to show:
    ///  - camera permission denied (with an "open settings" action)
    ///  - AR not supported / ARCore needs install / session errors
    ///  - no floor detected for a while (hint to move the phone, lighting, textured floor)
    /// </summary>
    public class ARGuidance : MonoBehaviour
    {
        [Tooltip("Seconds without any detected plane before the hint is shown.")]
        public float noPlaneHintAfterSeconds = 12f;

        public enum Issue { None, CameraDenied, Unsupported, NeedsInstall, SessionError, NoPlane }

        public Issue Current { get; private set; } = Issue.None;
        public bool PlaneDetected { get; private set; }

        /// <summary>(issue, user text, showSettingsButton)</summary>
        public event Action<Issue, string, bool> GuidanceChanged;

        ARPlaneManager planeManager;
        float planeTimer;

        void Start()
        {
            planeManager = FindFirstObjectByType<ARPlaneManager>();
            StartCoroutine(Watch());
        }

        IEnumerator Watch()
        {
            var tick = new WaitForSecondsRealtime(1f);
            while (true)
            {
                Evaluate();
                yield return tick;
            }
        }

        void Evaluate()
        {
            Issue issue = Issue.None;

#if UNITY_ANDROID && !UNITY_EDITOR
            if (!Permission.HasUserAuthorizedPermission(Permission.Camera)) issue = Issue.CameraDenied;
#endif
            if (issue == Issue.None)
            {
                switch (ARSession.state)
                {
                    case ARSessionState.Unsupported: issue = Issue.Unsupported; break;
                    case ARSessionState.NeedsInstall: issue = Issue.NeedsInstall; break;
                    case ARSessionState.None:
                    case ARSessionState.CheckingAvailability:
                    case ARSessionState.Installing:
                    case ARSessionState.Ready:
                    case ARSessionState.SessionInitializing:
                        break; // starting up, nothing to say yet
                    default:
                        if (ARSession.notTrackingReason == UnityEngine.XR.ARSubsystems.NotTrackingReason.CameraUnavailable)
                            issue = Issue.SessionError;
                        break;
                }
            }

            if (issue == Issue.None)
            {
                PlaneDetected = planeManager != null && planeManager.trackables.count > 0;
                if (PlaneDetected) planeTimer = 0f;
                else if (ARSession.state == ARSessionState.SessionTracking) planeTimer += 1f;
                if (!PlaneDetected && planeTimer >= noPlaneHintAfterSeconds) issue = Issue.NoPlane;
            }

            if (issue != Current)
            {
                Current = issue;
                GuidanceChanged?.Invoke(issue, TextFor(issue), issue == Issue.CameraDenied);
            }
        }

        static string TextFor(Issue issue) => issue switch
        {
            Issue.CameraDenied => "AR을 사용하려면 카메라 권한이 필요해요. 설정에서 카메라를 허용해 주세요.",
            Issue.Unsupported => "이 기기는 AR을 지원하지 않아요.",
            Issue.NeedsInstall => "AR 기능을 쓰려면 Google Play 서비스(AR) 설치가 필요해요.",
            Issue.SessionError => "카메라를 사용할 수 없어요. 다른 앱이 카메라를 쓰고 있는지 확인해 주세요.",
            Issue.NoPlane => "바닥이 아직 인식되지 않았어요. 폰을 천천히 움직여 바닥을 비춰 주세요. 밝은 곳의 무늬 있는 바닥이 잘 인식돼요.",
            _ => ""
        };

        /// <summary>Asks for the camera permission again, or opens this app's settings page when it was permanently denied.</summary>
        public void RequestCameraOrOpenSettings()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (Permission.ShouldShowRequestPermissionRationale(Permission.Camera))
            {
                Permission.RequestUserPermission(Permission.Camera);
                return;
            }
            try
            {
                using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                using var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
                using var uriClass = new AndroidJavaClass("android.net.Uri");
                using var uri = uriClass.CallStatic<AndroidJavaObject>("parse", "package:" + Application.identifier);
                using var intent = new AndroidJavaObject("android.content.Intent", "android.settings.APPLICATION_DETAILS_SETTINGS", uri);
                activity.Call("startActivity", intent);
            }
            catch (Exception e)
            {
                Debug.LogWarning($"[ARGuidance] could not open settings: {e.Message}");
                Permission.RequestUserPermission(Permission.Camera);
            }
#else
            Debug.Log("[ARGuidance] settings would open on device");
#endif
        }
    }
}
