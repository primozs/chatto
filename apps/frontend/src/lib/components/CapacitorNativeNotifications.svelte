<!--
@component

Keeps the Capacitor Android foreground notification service aligned with SPA
auth state. Starts after a usable bearer token exists; stops on logout / reauth.
-->
<script lang="ts">
  import {
    NATIVE_NOTIFICATIONS_READY_EVENT,
    selectNativeNotificationTarget,
    syncNativeNotificationService
  } from '$lib/capacitor/nativeNotifications';
  import { serverRegistry } from '$lib/client';
  import { getActiveServer } from '$lib/state/activeServer.svelte';

  const activeServerId = $derived(getActiveServer());
  const target = $derived(
    selectNativeNotificationTarget(serverRegistry.servers, activeServerId)
  );

  // Sync auth credentials to the native foreground service (outside world).
  // Also re-sync when glue finishes installing the bridge after first paint.
  $effect(() => {
    const next = target;
    void syncNativeNotificationService(next);

    const onReady = () => {
      void syncNativeNotificationService(next);
    };
    window.addEventListener(NATIVE_NOTIFICATIONS_READY_EVENT, onReady);
    return () => {
      window.removeEventListener(NATIVE_NOTIFICATIONS_READY_EVENT, onReady);
    };
  });
</script>
