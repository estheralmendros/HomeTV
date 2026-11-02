// Fullscreen on the shell's root: element fullscreen when the document
// allows it (standard or webkit-prefixed); else the iPhone's native player through
// `video.webkitEnterFullscreen()`; else no button. The root belongs to PlayerRoute, so
// fullscreen survives next-episode / next-part navigations.
import { useCallback, useEffect, useState } from 'react';

interface FullscreenDocument extends Document {
  webkitFullscreenEnabled?: boolean;
  webkitFullscreenElement?: Element | null;
  webkitExitFullscreen?: () => void;
}

interface FullscreenElement extends HTMLElement {
  webkitRequestFullscreen?: () => void;
}

interface IosVideo extends HTMLVideoElement {
  webkitEnterFullscreen?: () => void;
  webkitDisplayingFullscreen?: boolean;
}

const doc = () => document as FullscreenDocument;
const fullscreenElement = () => doc().fullscreenElement ?? doc().webkitFullscreenElement ?? null;

export interface FullscreenControls {
  available: boolean;
  active: boolean;
  toggle: () => void;
}

export function useFullscreen(root: HTMLElement, video: HTMLVideoElement): FullscreenControls {
  const elementApi = Boolean(doc().fullscreenEnabled || doc().webkitFullscreenEnabled);
  const iosApi = !elementApi && typeof (video as IosVideo).webkitEnterFullscreen === 'function';
  const [active, setActive] = useState(() => fullscreenElement() === root);

  useEffect(() => {
    const onChange = () => setActive(fullscreenElement() === root);
    const onBegin = () => setActive(true);
    const onEnd = () => setActive(false);
    document.addEventListener('fullscreenchange', onChange);
    document.addEventListener('webkitfullscreenchange', onChange);
    video.addEventListener('webkitbeginfullscreen', onBegin);
    video.addEventListener('webkitendfullscreen', onEnd);
    return () => {
      document.removeEventListener('fullscreenchange', onChange);
      document.removeEventListener('webkitfullscreenchange', onChange);
      video.removeEventListener('webkitbeginfullscreen', onBegin);
      video.removeEventListener('webkitendfullscreen', onEnd);
    };
  }, [root, video]);

  const toggle = useCallback(() => {
    const ignore = () => undefined;
    if (elementApi) {
      if (fullscreenElement()) {
        const d = doc();
        if (d.exitFullscreen) void d.exitFullscreen().catch(ignore);
        else d.webkitExitFullscreen?.();
      } else {
        const el = root as FullscreenElement;
        if (el.requestFullscreen) void el.requestFullscreen().catch(ignore);
        else el.webkitRequestFullscreen?.();
      }
    } else if (iosApi) {
      (video as IosVideo).webkitEnterFullscreen?.();
    }
  }, [elementApi, iosApi, root, video]);

  return { available: elementApi || iosApi, active, toggle };
}
