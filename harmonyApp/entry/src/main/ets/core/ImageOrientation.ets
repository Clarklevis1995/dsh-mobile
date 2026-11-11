export interface ImageOrientationPlan { horizontal: boolean; vertical: boolean; rotation: number; }
/** 与本机 Image Kit PropertyKey.ORIENTATION 的八种 EXIF 方向一致，先镜像再旋转。 */
export function imageOrientation(value: string): ImageOrientationPlan {
  switch (value) {
    case '2': case 'Top-right': return { horizontal: true, vertical: false, rotation: 0 };
    case '3': case 'Bottom-right': return { horizontal: false, vertical: false, rotation: 180 };
    case '4': case 'Bottom-left': return { horizontal: false, vertical: true, rotation: 0 };
    case '5': case 'Left-top': return { horizontal: true, vertical: false, rotation: 270 };
    case '6': case 'Right-top': return { horizontal: false, vertical: false, rotation: 90 };
    case '7': case 'Right-bottom': return { horizontal: true, vertical: false, rotation: 90 };
    case '8': case 'Left-bottom': return { horizontal: false, vertical: false, rotation: 270 };
    default: return { horizontal: false, vertical: false, rotation: 0 };
  }
}
