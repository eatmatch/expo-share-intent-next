import { EventSubscription } from "expo-modules-core";
import { Image } from "react-native";

import { ExpoShareIntent } from "./ExpoShareIntent";
import { LOG_TAG } from "./constants";
import type {
  DonateSendMessageOptions,
  ExpoShareIntentEvents,
  PublishDirectShareTargetsContact,
} from "./types";
import { resolveImageSource } from "./utils";

export async function getShareIntent(url?: string): Promise<string> {
  if (!ExpoShareIntent?.getShareIntent) {
    throw new Error("ExpoShareIntent module is not available");
  }

  return ExpoShareIntent.getShareIntent(url);
}

export async function clearShareIntent(key: string): Promise<void> {
  if (!ExpoShareIntent?.clearShareIntent) {
    throw new Error("ExpoShareIntent module is not available");
  }

  return ExpoShareIntent.clearShareIntent(key);
}

export async function donateSendMessage(
  options: DonateSendMessageOptions,
): Promise<void> {
  if (!ExpoShareIntent?.donateSendMessage) {
    throw new Error("ExpoShareIntent module is not available");
  }

  const { conversationId, name, image, content } = options;
  if (!conversationId || !name) {
    console.error(
      LOG_TAG,
      `donateSendMessage requires both conversationId and name`,
    );
    return;
  }

  const imageSource = image ? resolveImageSource(image) : undefined;

  return ExpoShareIntent.donateSendMessage(
    conversationId,
    name,
    imageSource?.uri,
    content,
  );
}

export async function publishDirectShareTargets(
  contacts: PublishDirectShareTargetsContact[],
): Promise<boolean> {
  if (!ExpoShareIntent?.publishDirectShareTargets) {
    throw new Error("ExpoShareIntent module is not available");
  }

  const mappedContacts = contacts.map((contact) => {
    if (typeof contact.image === "number") {
      return {
        id: contact.id,
        name: contact.name,
        imageURL: Image.resolveAssetSource(contact.image).uri,
      };
    }

    return contact;
  });

  return ExpoShareIntent.publishDirectShareTargets(mappedContacts);
}

export function reportShortcutUsed(shortcutId: string): void {
  if (!ExpoShareIntent?.reportShortcutUsed) {
    throw new Error("ExpoShareIntent module is not available");
  }

  return ExpoShareIntent.reportShortcutUsed(shortcutId);
}

export function removeShortcut(shortcutId: string): void {
  if (!ExpoShareIntent?.removeShortcut) {
    throw new Error("ExpoShareIntent module is not available");
  }

  return ExpoShareIntent.removeShortcut(shortcutId);
}

export function removeAllShortcuts(): void {
  if (!ExpoShareIntent?.removeAllShortcuts) {
    throw new Error("ExpoShareIntent module is not available");
  }

  return ExpoShareIntent.removeAllShortcuts();
}

export function hasShareIntent(key: string): Promise<boolean> {
  if (!ExpoShareIntent?.hasShareIntent) {
    throw new Error("ExpoShareIntent module is not available");
  }

  return ExpoShareIntent.hasShareIntent(key);
}

export function addShareIntentListener<T extends keyof ExpoShareIntentEvents>(
  eventName: T,
  listener: ExpoShareIntentEvents[T],
): EventSubscription {
  if (!ExpoShareIntent) {
    throw new Error("ExpoShareIntent module is not available");
  }

  return ExpoShareIntent.addListener(eventName, listener);
}
