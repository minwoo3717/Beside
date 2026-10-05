using System.Collections.Generic;
using UnityEngine;
using UnityEngine.XR.ARFoundation;
using UnityEngine.XR.ARSubsystems;
using UnityEngine.XR.Interaction.Toolkit.Samples.StarterAssets;

namespace Beside.AR
{
    /// <summary>
    /// "화면 가운데에 바로 놓기": raycasts from the screen center onto detected planes and asks the
    /// template's ObjectSpawner to spawn there (same path as a tap, so metrics and events still fire).
    /// </summary>
    public class CenterPlacer : MonoBehaviour
    {
        ARRaycastManager raycaster;
        ObjectSpawner spawner;
        readonly List<ARRaycastHit> hits = new List<ARRaycastHit>();

        void Awake()
        {
            raycaster = FindFirstObjectByType<ARRaycastManager>();
            spawner = FindFirstObjectByType<ObjectSpawner>();
        }

        /// <summary>True when a plane is under the screen center right now.</summary>
        public bool CanPlace()
        {
            if (raycaster == null) return false;
            var center = new Vector2(Screen.width * 0.5f, Screen.height * 0.5f);
            return raycaster.Raycast(center, hits, TrackableType.PlaneWithinPolygon);
        }

        /// <summary>Spawns the current spawner option at the screen center. Returns false when no plane is there.</summary>
        public bool PlaceAtCenter()
        {
            if (raycaster == null || spawner == null) return false;
            var center = new Vector2(Screen.width * 0.5f, Screen.height * 0.5f);
            if (!raycaster.Raycast(center, hits, TrackableType.PlaneWithinPolygon)) return false;
            Pose pose = hits[0].pose;
            return spawner.TrySpawnObject(pose.position, pose.up);
        }
    }
}
