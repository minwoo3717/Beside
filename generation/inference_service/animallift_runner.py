"""Loads AnimalLift (https://github.com/Chuny1/AnimalLift) once and predicts one image at a time.

RUNNER_INTERFACE — what app.py needs from any model runner (swap with BESIDE_RUNNER=module:Class):
    __init__(model_dir: str, *, model_name: str, checkpoint: str|None, device: str, nfe: int|None,
             cfg_scale: float|None, vae_local_path: str|None)        # unknown kwargs may be ignored
    predict(image_path: str, out_dir: str) -> Prediction             # writes mesh.obj (with UVs) + uv.png (+ hair.npz)
    .version: str        # "<model_name>@<code commit>:<weights>", stored per run in job_runs.model_version
    .model_params: int   # METRICS modelParams
    .device              # for the log line
An improved AnimalLift fork with the same functions needs no new runner: point BESIDE_MODEL_DIR at the fork and
set BESIDE_MODEL_NAME (e.g. animallift-v2). A different model writes its own runner with this interface.

Reuses the repo's own code (animallift.inference / animallift.model) so results match `python test.py`.
Writes the same three files AnimalLift's test.py writes, under one folder per job:
    <out_dir>/mesh.obj   reference topology + UVs (SMAL-style template, head on +X)
    <out_dir>/uv.png     predicted UV texture
    <out_dir>/hair.npz   quantized hair offsets
"""
from __future__ import annotations

import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace



def load_rgb(image_path: str):
    """The photo upright and on white, as RGB: EXIF Orientation applied (phones store portraits sideways with a tag;
    PIL does not turn them by itself), then alpha composited on a white background as AnimalLift's test.py does."""
    from PIL import Image, ImageOps

    img = ImageOps.exif_transpose(Image.open(image_path)).convert("RGBA")
    bg = Image.new("RGBA", img.size, (255, 255, 255, 255))
    return Image.alpha_composite(bg, img).convert("RGB")

@dataclass
class Prediction:
    mesh_obj: Path
    uv_png: Path
    hair_npz: Path | None          # None when the model has no hair output (no fur maps are written)
    infer_ms: int
    gpu_peak_mb: int | None


class AnimalLiftRunner:
    def __init__(self, repo_dir: str, checkpoint: str | None = None, device: str = "cuda",
                 nfe: int | None = None, cfg_scale: float | None = None, seed: int = 42,
                 vae_local_path: str | None = None, model_name: str = "animallift-baseline", **_ignored):
        self.repo = Path(repo_dir).resolve()
        if not (self.repo / "animallift" / "inference.py").is_file():
            raise FileNotFoundError(f"not an AnimalLift checkout: {self.repo}")
        ckpt_path = Path(checkpoint).resolve() if checkpoint else self.repo / "ckpt" / "latest.pt"
        ref_mesh = self.repo / "assets" / "reference.obj"
        for p, what in ((ckpt_path, "checkpoint (hf download Chunyi99/AnimalLift --local-dir ckpt)"), (ref_mesh, "assets/reference.obj")):
            if not p.is_file():
                raise FileNotFoundError(f"missing {what}: {p}")
        if str(self.repo) not in sys.path:
            sys.path.insert(0, str(self.repo))

        import torch  # noqa: WPS433  (heavy imports only when the model is really used)
        from torchvision import transforms
        from animallift.inference import build_runtime_args, import_training_module, load_models

        self.torch = torch
        self.seed = seed
        cli = SimpleNamespace(
            module_name="animallift.model", resume=str(ckpt_path), flat_val_image_dir=None,
            reference_mesh_path=str(ref_mesh), output_dir=None, device=device, max_images=None,
            batch_size=1, seed=seed, val_nfe=nfe, val_cfg_scale=cfg_scale,
            vae_local_files_only=False, vae_slicing=True, vae_local_path=vae_local_path,
        )
        self.train_mod = import_training_module("animallift.model")
        ckpt = torch.load(str(ckpt_path), map_location="cpu")
        self.rt = build_runtime_args(cli, ckpt, self.train_mod)
        self.device = torch.device(device if torch.cuda.is_available() else "cpu")

        _, self.faces, self.verts_uvs, self.faces_uvs = self.train_mod.load_obj(str(ref_mesh))
        self.vae, self.model, self.template_verts = load_models(self.rt, self.train_mod, self.device, ckpt)
        del ckpt
        self.model_params = int(sum(p.numel() for p in self.model.parameters()))
        self.tfm = transforms.Compose([
            transforms.Resize((self.rt.ctrl_size, self.rt.ctrl_size), transforms.InterpolationMode.BICUBIC, antialias=True),
            transforms.ToTensor(),
            transforms.Normalize([0.5] * 3, [0.5] * 3),
        ])
        self.version = f"{model_name}@{_git_short(self.repo)}:{ckpt_path.stem}"

    def predict(self, image_path: str, out_dir: str) -> Prediction:
        """One image -> mesh.obj, uv.png, hair.npz in out_dir. Same steps as animallift.inference.run_flat_validation."""
        torch = self.torch
        from PIL import Image
        from torchvision.utils import save_image

        out = Path(out_dir)
        out.mkdir(parents=True, exist_ok=True)
        self.train_mod.set_seed(self.seed)              # same photo -> same result
        if self.device.type == "cuda":
            torch.cuda.reset_peak_memory_stats(self.device)
        started = time.perf_counter()

        img = load_rgb(image_path)

        with torch.no_grad():
            ctrl = self.tfm(img).unsqueeze(0).to(self.device)
            outs = self.model.infer_all(ctrl, nfe=int(self.rt.val_nfe), cfg_scale=float(self.rt.val_cfg_scale))
            texture = self.vae.decode(outs["z_pred"]).detach().cpu()
            hair = outs["pred_hair"].detach().cpu()
            verts = (self.template_verts.unsqueeze(0) + outs["pred_offsets"]).detach().cpu().numpy()

        mesh_obj, uv_png, hair_npz = out / "mesh.obj", out / "uv.png", out / "hair.npz"
        save_image((texture[0] * 0.5 + 0.5).clamp(0, 1), str(uv_png))
        self.train_mod.save_hair_offset_npz_compatible(hair[0], str(hair_npz), hair_norm_scale=float(self.rt.hair_norm_scale))
        self.train_mod.save_obj(str(mesh_obj), verts[0], self.faces, self.verts_uvs, self.faces_uvs)

        infer_ms = int((time.perf_counter() - started) * 1000)
        peak = None
        if self.device.type == "cuda":
            peak = int(torch.cuda.max_memory_allocated(self.device) / (1024 * 1024))
            del ctrl, outs
            torch.cuda.empty_cache()
        return Prediction(mesh_obj, uv_png, hair_npz, infer_ms, peak)


def _git_short(repo: Path) -> str:
    try:
        return subprocess.run(["git", "-C", str(repo), "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True, timeout=5).stdout.strip() or "nogit"
    except Exception:  # noqa: BLE001
        return "nogit"
