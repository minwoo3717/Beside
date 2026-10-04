using UnityEngine;

namespace Beside.Job
{
    /// <summary>
    /// Owns the upload, polling and download lifecycle once implemented.
    /// </summary>
    public class JobManager : MonoBehaviour
    {
        [SerializeField] private string baseUrl = "";
        [SerializeField] private float pollingIntervalSeconds = 2f;
        [SerializeField] private float maxWaitSeconds = 60f;

        public void StartJob(string[] photoPaths)
        {
            // TODO: Construct BesideApiClient with baseUrl and upload the photos.
            // Poll PENDING / PROCESSING, download on COMPLETED, stop on FAILED (retry: POST /api/v1/jobs/{jobId}/retry).
            // Apply pollingIntervalSeconds and maxWaitSeconds.
        }

        public void CancelJob()
        {
            // TODO: Stop polling and abort the active UnityWebRequest.
        }

        public void OnModelDownloaded(string filePath)
        {
            // TODO: Notify the UI and provide a hook for a future model/AR screen.
        }
    }
}
