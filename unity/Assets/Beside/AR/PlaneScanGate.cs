using UnityEngine;
using UnityEngine.XR.ARFoundation;

namespace Beside.AR
{
    /// <summary>
    /// Turns floor detection on only when the user is on the AR screen.
    /// While the other screens are open the phone is usually pointed at random places; planes found there
    /// would win over the spot the user actually wants. So:
    ///  - StopScan: plane detection off, existing plane visuals hidden.
    ///  - BeginScan(freshStart: true): AR session reset (old planes removed), then detection on — the floor in
    ///    front of the user is found from scratch. Used when nothing is placed yet.
    ///  - BeginScan(freshStart: false): detection on, old planes shown again (a placed pet keeps its spot).
    /// The camera feed keeps running the whole time; only plane finding is paused.
    /// </summary>
    public class PlaneScanGate : MonoBehaviour
    {
        ARPlaneManager planes;
        ARSession session;

        public bool Scanning { get; private set; } = true;

        bool Find()
        {
            if (planes == null) planes = FindFirstObjectByType<ARPlaneManager>();
            if (session == null) session = FindFirstObjectByType<ARSession>();
            return planes != null;
        }

        public void StopScan()
        {
            if (!Find()) return;
            planes.enabled = false;
            SetPlanesActive(false);
            Scanning = false;
        }

        public void BeginScan(bool freshStart)
        {
            if (!Find() || Scanning) return;
            if (freshStart && session != null)
            {
                session.Reset();                 // drops every plane found so far
                Debug.Log("[PlaneScanGate] AR session reset, scanning from scratch");
            }
            else
            {
                SetPlanesActive(true);
            }
            planes.enabled = true;
            Scanning = true;
        }

        void SetPlanesActive(bool on)
        {
            foreach (var p in planes.trackables)
                if (p != null) p.gameObject.SetActive(on);
        }
    }
}
