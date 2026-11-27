// Validate GLB files with the Khronos glTF-Validator and check docs/asset/GLB_SPEC.md limits.
// usage: node validate.cjs <path to node_modules/gltf-validator> <out dir> <glb>...
const fs = require('fs');
const path = require('path');
const [, , pkgDir, outDir, ...files] = process.argv;
const validator = require(pkgDir);

function pngSize(buf) {   // IHDR width/height of an embedded PNG
  if (buf.length < 24 || buf.readUInt32BE(0) !== 0x89504e47) return null;
  return [buf.readUInt32BE(16), buf.readUInt32BE(20)];
}

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const rows = [];
  for (const file of files) {
    const bytes = new Uint8Array(fs.readFileSync(file));
    const report = await validator.validateBytes(bytes, { uri: path.basename(file), maxIssues: 1000 });
    fs.writeFileSync(path.join(outDir, path.basename(file) + '.report.json'), JSON.stringify(report, null, 2));
    // own GLB_SPEC checks from the JSON chunk
    const b = Buffer.from(bytes);
    const jsonLen = b.readUInt32LE(12);
    const gltf = JSON.parse(b.slice(20, 20 + jsonLen).toString('utf8'));
    const binStart = 20 + jsonLen + 8;
    const imgs = (gltf.images || []).map((im) => {
      if (im.uri) return { name: im.name || '', external: true };
      const bv = gltf.bufferViews[im.bufferView];
      const data = b.slice(binStart + (bv.byteOffset || 0), binStart + (bv.byteOffset || 0) + bv.byteLength);
      return { name: im.name || '(body)', mime: im.mimeType, size: pngSize(data) };
    });
    const texSources = new Set((gltf.textures || []).map((t) => t.source));
    const hairIdx = (gltf.images || []).map((im, i) => [im.name, i]).filter(([n]) => n === 'hair_length' || n === 'hair_flow').map(([, i]) => i);
    const i = report.info || {};
    const row = {
      file: path.basename(file),
      bytes: bytes.length,
      validatorVersion: report.validatorVersion,
      errors: report.issues.numErrors, warnings: report.issues.numWarnings,
      infos: report.issues.numInfos, hints: report.issues.numHints,
      messages: report.issues.messages.map((m) => `${['ERR', 'WARN', 'INFO', 'HINT'][m.severity]} ${m.code} ${m.pointer || ''} ${m.message}`.trim()),
      generator: i.generator, triangles: i.totalTriangleCount, vertices: i.totalVertexCount,
      animations: i.animationCount, skins: i.hasSkins, materials: i.materialCount, drawCalls: i.drawCallCount,
      extensionsUsed: gltf.extensionsUsed || [], buffers: (gltf.buffers || []).length,
      externalBuffer: (gltf.buffers || []).some((x) => x.uri), images: imgs,
      hairMapsReferencedByTexture: hairIdx.some((x) => texSources.has(x)),
      extrasBeside: gltf.extras && gltf.extras.beside ? Object.keys(gltf.extras.beside) : [],
      animationNames: (gltf.animations || []).map((a) => a.name),
    };
    row.spec = {
      'validator errors 0': row.errors === 0,
      'size <= 10 MB': row.bytes <= 10 * 1024 * 1024,
      'one buffer, no external uri': row.buffers === 1 && !row.externalBuffer && !imgs.some((x) => x.external),
      'no extensions': row.extensionsUsed.length === 0,
      'triangles <= 20000': typeof row.triangles === 'number' && row.triangles <= 20000,
      'textures <= 2048': imgs.every((x) => x.size && x.size[0] <= 2048 && x.size[1] <= 2048),
      'hair maps not used by a texture': !row.hairMapsReferencedByTexture,
    };
    rows.push(row);
  }
  fs.writeFileSync(path.join(outDir, 'summary.json'), JSON.stringify(rows, null, 2));
  for (const r of rows) {
    console.log(`== ${r.file} ${r.bytes} B  validator ${r.validatorVersion}: ${r.errors} errors, ${r.warnings} warnings, ${r.infos} infos, ${r.hints} hints`);
    console.log(`   tri ${r.triangles} vtx ${r.vertices} anim ${r.animations} skins ${r.skins} mats ${r.materials} draws ${r.drawCalls} ext [${r.extensionsUsed}] extras.beside [${r.extrasBeside}]`);
    console.log(`   images ${r.images.map((x) => `${x.name}:${x.size ? x.size.join('x') : '?'}`).join(', ')}  anims [${r.animationNames}]`);
    for (const m of r.messages.slice(0, 12)) console.log('   - ' + m);
    if (r.messages.length > 12) console.log(`   ... ${r.messages.length - 12} more`);
    console.log('   spec: ' + Object.entries(r.spec).map(([k, v]) => `${v ? 'ok' : 'NO'} ${k}`).join(' | '));
  }
})().catch((e) => { console.error(e); process.exit(1); });
