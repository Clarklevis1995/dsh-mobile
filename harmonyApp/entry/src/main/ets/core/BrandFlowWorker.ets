import { renderBrandFlow } from './BrandFlow';

@Concurrent
export function prepareBrandFlow(width: number, height: number, aspect: number, seconds: number, wide: boolean): ArrayBuffer { return renderBrandFlow(width,height,aspect,seconds,wide); }
