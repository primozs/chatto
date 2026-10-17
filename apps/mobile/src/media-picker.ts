/**
 * Native multi-select photo/video picker for the Capacitor shell.
 *
 * Exposes `window.__CHATTO_PICK_MEDIA` so the SPA can replace
 * `<input type="file">` when `window.__CHATTO_CAPACITOR` is set.
 * Returns browser `File` objects ready for the existing upload pipeline.
 */

import { Capacitor } from '@capacitor/core';
import { FilePicker, type PickedFile } from '@capawesome/capacitor-file-picker';

export type ChattoPickMediaOptions = {
  /**
   * Max files the user may select. `0` means unlimited (plugin default).
   * On Android only `0` and `1` are meaningfully distinct for some pickers.
   */
  limit?: number;
  /** Restrict to images only, videos only, or either (default). */
  kind?: 'images' | 'videos' | 'media';
};

declare global {
  interface Window {
    /**
     * Open the native gallery / media picker and resolve to `File`s.
     * Rejects if the user cancels or the platform cannot pick.
     */
    __CHATTO_PICK_MEDIA?: (options?: ChattoPickMediaOptions) => Promise<File[]>;
  }
}

async function base64ToFile(name: string, mimeType: string, data: string): Promise<File> {
  const binary = atob(data);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return new File([bytes], name, { type: mimeType });
}

async function pickedFileToFile(picked: PickedFile): Promise<File | null> {
  const mimeType = picked.mimeType || 'application/octet-stream';
  const name = picked.name || 'media';

  if (picked.blob) {
    return new File([picked.blob], name, { type: mimeType });
  }

  if (picked.path) {
    const src = Capacitor.convertFileSrc(picked.path);
    const response = await fetch(src);
    if (!response.ok) {
      throw new Error(`failed to read picked file (${response.status})`);
    }
    const blob = await response.blob();
    return new File([blob], name, { type: mimeType || blob.type || 'application/octet-stream' });
  }

  if (picked.data) {
    return base64ToFile(name, mimeType, picked.data);
  }

  return null;
}

/**
 * Open the native media picker and return File objects for upload.
 */
export async function pickMediaFiles(
  options: ChattoPickMediaOptions = {}
): Promise<File[]> {
  if (!Capacitor.isNativePlatform()) {
    throw new Error('native media picker is only available in Capacitor');
  }

  const limit = options.limit ?? 0;
  const kind = options.kind ?? 'media';

  const result =
    kind === 'images'
      ? await FilePicker.pickImages({ limit })
      : kind === 'videos'
        ? await FilePicker.pickVideos({ limit })
        : await FilePicker.pickMedia({ limit });

  const files: File[] = [];
  for (const picked of result.files) {
    const file = await pickedFileToFile(picked);
    if (file) files.push(file);
  }
  return files;
}

/** Bind the picker onto `window` for the SPA to call. */
export function installMediaPicker(): void {
  window.__CHATTO_PICK_MEDIA = pickMediaFiles;
}
