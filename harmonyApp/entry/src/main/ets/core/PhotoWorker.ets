import { fileIo as fs } from '@kit.CoreFileKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { image } from '@kit.ImageKit';
import { imageOrientation } from './ImageOrientation';

/** 解码时限制像素尺寸，规范化方向后重新编码；原始 EXIF 不进入发送副本。 */
@Concurrent
export async function preparePhoto(uri: string, destination: string): Promise<string> {
  const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
  try {
    if (fs.statSync(file.fd).size > 67108864) { throw new Error('原图过大'); }
    const source = image.createImageSource(file.fd);
    try {
      const info = await source.getImageInfo(); const width = info.size.width; const height = info.size.height;
      if (width < 1 || height < 1 || width * height > 100000000) { throw new Error('图片尺寸无效'); }
      const ratio = Math.min(1, 2048 / Math.max(width, height));
      const pixel = await source.createPixelMap({ editable: true, desiredSize: { width: Math.max(1, Math.floor(width * ratio)), height: Math.max(1, Math.floor(height * ratio)) }, desiredPixelFormat: image.PixelMapFormat.RGBA_8888 });
      try {
        let orientation = 'Top-left';
        try { orientation = await source.getImageProperty(image.PropertyKey.ORIENTATION); } catch (_) {}
        const plan = imageOrientation(orientation);
        if (plan.horizontal || plan.vertical) { await pixel.flip(plan.horizontal, plan.vertical); }
        if (plan.rotation) { await pixel.rotate(plan.rotation); }
        const packer = image.createImagePacker();
        try {
          let output = await packer.packing(pixel, { format: 'image/jpeg', quality: 85 });
          for (let attempt = 0; output.byteLength > 1048576 && attempt < 3; attempt++) {
            await pixel.scale(.75, .75); output = await packer.packing(pixel, { format: 'image/jpeg', quality: 72 });
          }
          if (output.byteLength > 1048576) { throw new Error('图片压缩后仍过大'); }
          const target = fs.openSync(destination, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
          try { if (fs.writeSync(target.fd, output) !== output.byteLength) { throw new Error('图片保存不完整'); } } finally { fs.closeSync(target); }
          const decoded = await pixel.getImageInfo(); const md = cryptoFramework.createMd('SHA256'); await md.update({ data: new Uint8Array(output) });
          const digest = await md.digest(); let sha = ''; digest.data.forEach(value => sha += value.toString(16).padStart(2, '0'));
          return JSON.stringify({ size: output.byteLength, sha256: sha, width: decoded.size.width, height: decoded.size.height });
        } finally { await packer.release(); }
      } finally { await pixel.release(); }
    } finally { await source.release(); }
  } finally { fs.closeSync(file); }
}

