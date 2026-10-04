using System;
using System.Collections;
using UnityEngine.Networking;

namespace Beside.Api
{
    /// <summary>
    /// Backend API boundary. UnityWebRequest operations will be implemented later.
    /// </summary>
    public sealed class BesideApiClient
    {
        public string BaseUrl { get; private set; }

        public BesideApiClient(string baseUrl)
        {
            BaseUrl = baseUrl;
        }

        // TODO: UnityWebRequest.Post with repeated multipart "photos" parts.
        // POST /api/v1/jobs returns 202 + { jobId } and Location: /api/v1/jobs/{jobId} (ApiV1Routes.Jobs).
        public IEnumerator UploadPhotos(
            string[] photoPaths, Action<string> onJobCreated, Action<string> onError)
        {
            throw new NotImplementedException("Photo upload is not implemented yet.");
        }

        // TODO: UnityWebRequest.Get /api/v1/jobs/{jobId} (ApiV1Routes.Job).
        // Deserialize JobResponse (Newtonsoft Json); unknown status -> JobStatus.Unknown via ApiEnums.ParseJobStatus.
        public IEnumerator GetJobStatus(
            string jobId, Action<string> onStatusReceived, Action<string> onError)
        {
            throw new NotImplementedException("Job status lookup is not implemented yet.");
        }

        // TODO: UnityWebRequest + DownloadHandlerFile for JobResponse.asset.url (/api/v1/jobs/{jobId}/asset?variant=base).
        // Save under Application.persistentDataPath/models/{jobId}-base.glb and return the local path.
        public IEnumerator DownloadResult(
            string jobId, Action<string> onModelDownloaded, Action<string> onError)
        {
            throw new NotImplementedException("Model download is not implemented yet.");
        }
    }
}
