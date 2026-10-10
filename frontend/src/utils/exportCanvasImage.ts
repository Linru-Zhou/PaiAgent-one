import { Node, getNodesBounds, getViewportForBounds } from '@xyflow/react';

/**
 * 把画布渲染成高清 PNG（不依赖第三方库）。
 *
 * 做法：克隆 ReactFlow 的 viewport 节点，把每个元素当前的计算样式逐个内联进去（克隆出来的节点脱离了页面样式表），
 * 放进 SVG 的 foreignObject，让浏览器把这张 SVG 当图片加载，再画到 canvas 上导出。
 * 视口按"所有节点的包围盒"重新计算，所以不管当前画布缩放 / 平移到哪里，导出的都是完整的图。
 */
const inlineComputedStyles = (source: Element, target: Element) => {
  const computed = getComputedStyle(source);
  let css = '';
  for (const property of Array.from(computed)) {
    css += `${property}:${computed.getPropertyValue(property)};`;
  }
  target.setAttribute('style', css);
  for (let i = 0; i < source.children.length; i++) {
    inlineComputedStyles(source.children[i], target.children[i]);
  }
};

export const renderCanvasToPng = async (nodes: Node[], options: { scale?: number; padding?: number; maxWidth?: number } = {}): Promise<string> => {
  const viewport = document.querySelector('.react-flow__viewport');
  if (!viewport || nodes.length === 0) {
    throw new Error('画布上没有可导出的内容');
  }
  const scale = options.scale ?? 2;
  const bounds = getNodesBounds(nodes);
  const padding = options.padding ?? 48;
  const width = Math.min(Math.ceil(bounds.width + padding * 2), options.maxWidth ?? 2400);
  const height = Math.ceil((bounds.height + padding * 2) * (width / (bounds.width + padding * 2)));
  const view = getViewportForBounds(bounds, width, height, 0.1, 2, padding / Math.max(width, height));

  const clone = viewport.cloneNode(true) as HTMLElement;
  inlineComputedStyles(viewport, clone);
  clone.style.transform = `translate(${view.x}px, ${view.y}px) scale(${view.zoom})`;
  clone.style.transformOrigin = '0 0';
  clone.style.position = 'absolute';
  clone.style.top = '0';
  clone.style.left = '0';

  const wrapper = document.createElement('div');
  wrapper.setAttribute('xmlns', 'http://www.w3.org/1999/xhtml');
  wrapper.style.cssText = `position:relative;overflow:hidden;width:${width}px;height:${height}px;background:#f8fafc;`;
  wrapper.appendChild(clone);

  const markup = new XMLSerializer().serializeToString(wrapper);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><foreignObject width="100%" height="100%">${markup}</foreignObject></svg>`;

  const image = new Image();
  await new Promise<void>((resolve, reject) => {
    image.onload = () => resolve();
    image.onerror = () => reject(new Error('流程图渲染失败'));
    image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  });

  const canvas = document.createElement('canvas');
  canvas.width = width * scale;
  canvas.height = height * scale;
  const context = canvas.getContext('2d');
  if (!context) {
    throw new Error('浏览器不支持 canvas');
  }
  context.scale(scale, scale);
  context.fillStyle = '#f8fafc';
  context.fillRect(0, 0, width, height);
  context.drawImage(image, 0, 0, width, height);
  return canvas.toDataURL('image/png');
};
