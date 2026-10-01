using Beside.Job;
using UnityEngine;

namespace Beside.UI
{
    /// <summary>
    /// UI event bindings only; API calls and polling belong to other classes.
    /// </summary>
    public class MainUIController : MonoBehaviour
    {
        [SerializeField] private JobManager jobManager;
        [SerializeField] private string[] editorTestImagePaths = new string[0];

        public void SelectPhotos()
        {
            // TODO: Validate editorTestImagePaths and display the selected count.
            // Android gallery selection will be added in a separate step.
        }

        public void RequestGeneration()
        {
            // TODO: Pass the selected paths to jobManager.StartJob.
        }

        public void ShowStatus(string status)
        {
            // TODO: Display uploading / PENDING / PROCESSING / COMPLETED / FAILED.
        }

        public void ShowError(string message)
        {
            // TODO: Display a short error message.
        }

        public void ShowDownloadCompleted(string filePath)
        {
            // TODO: Display completion and log the local model path.
        }
    }
}
