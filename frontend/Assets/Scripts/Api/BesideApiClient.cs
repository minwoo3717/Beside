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
        // POST /api/jobs returns 202 with no body; extract jobId from Location.
        public IEnumerator UploadPhotos(
            string[] photoPaths, Action<string> onJobCreated, Action<string> onError)
        {
            throw new NotImplementedException("Photo upload is not implemented yet.");
        }

        // TODO: UnityWebRequest.Get /api/jobs/{jobId}.
        // Parse the existing JSON fields "id" and "status", then return status.
        public IEnumerator GetJobStatus(
            string jobId, Action<string> onStatusReceived, Action<string> onError)
        {
            throw new NotImplementedException("Job status lookup is not implemented yet.");
        }

        // TODO: UnityWebRequest + DownloadHandlerFile for /api/jobs/{jobId}/result.
        // Save under Application.persistentDataPath and return the local path.
        public IEnumerator DownloadResult(
            string jobId, Action<string> onModelDownloaded, Action<string> onError)
        {
            throw new NotImplementedException("Model download is not implemented yet.");
        }
    }
}
