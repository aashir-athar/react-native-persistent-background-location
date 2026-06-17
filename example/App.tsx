/**
 * Example consumer for `react-native-persistent-background-location`.
 *
 * A minimal run-tracker: request permission, start background tracking with a
 * foreground-service notification + motion gating, watch fixes stream in live
 * (including after the app is backgrounded / swiped on Android, or relaunched
 * via significant-location-change on iOS), and inspect the offline buffer.
 *
 * This is a Nitro module — it needs a dev build, not Expo Go:
 *   cd example
 *   npx expo prebuild --clean
 *   npx expo run:android   # or: npx expo run:ios --device
 *
 * This file ships with the GitHub repo only, not the npm tarball.
 */

import * as Bg from 'react-native-persistent-background-location';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, FlatList, SafeAreaView, StyleSheet, Text, View } from 'react-native';

export default function App() {
  const [authorization, setAuthorization] = useState<string>('unknown');
  const [running, setRunning] = useState(false);
  const [bufferedCount, setBufferedCount] = useState(0);
  const [fixes, setFixes] = useState<Bg.LocationFix[]>([]);
  const distanceRef = useRef(0);
  const lastRef = useRef<Bg.LocationFix | null>(null);
  const [distance, setDistance] = useState(0);

  const refreshStatus = useCallback(async () => {
    const status = await Bg.getStatus();
    setRunning(status.running);
    setBufferedCount(status.bufferedCount);
    setAuthorization(status.authorization);
  }, []);

  useEffect(() => {
    Bg.getPermissionStatus().then((p) => setAuthorization(p.status));
    refreshStatus();

    const locationSub = Bg.onLocation((fix) => {
      setFixes((prev) => [fix, ...prev].slice(0, 50));
      const last = lastRef.current;
      if (last) {
        distanceRef.current += haversine(last.latitude, last.longitude, fix.latitude, fix.longitude);
        setDistance(distanceRef.current);
      }
      lastRef.current = fix;
    });

    const motionSub = Bg.onMotionChange(({ isMoving, activity }) => {
      console.log(`[motion] ${isMoving ? 'moving' : 'stationary'} (${activity})`);
    });

    const syncSub = Bg.onSync((result) => {
      console.log(`[sync] ${result.success ? 'ok' : 'fail'} count=${result.count}`);
      refreshStatus();
    });

    const errorSub = Bg.onError((e) => console.warn(`[error] ${e.code}: ${e.message}`));

    return () => {
      locationSub.remove();
      motionSub.remove();
      syncSub.remove();
      errorSub.remove();
    };
  }, [refreshStatus]);

  const onStart = useCallback(async () => {
    const perm = await Bg.requestPermissions({ background: true });
    setAuthorization(perm.status);
    if (perm.foreground !== 'granted') return;

    await Bg.start({
      accuracy: 'high',
      distanceFilter: 10,
      interval: 5000,
      restartOnBoot: true,
      useSignificantChanges: true,
      foregroundService: {
        notificationTitle: 'Run in progress',
        notificationBody: 'Tracking your route — tap to return.',
      },
      buffer: { persist: true /* , syncUrl: 'https://api.example.com/locations' */ },
      motion: { enabled: true },
    });
    refreshStatus();
  }, [refreshStatus]);

  const onStop = useCallback(async () => {
    await Bg.stop();
    refreshStatus();
  }, [refreshStatus]);

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Persistent Background Location</Text>
      <View style={styles.row}>
        <Stat label="Auth" value={authorization} />
        <Stat label="Running" value={running ? 'yes' : 'no'} />
        <Stat label="Buffered" value={String(bufferedCount)} />
        <Stat label="Distance" value={`${(distance / 1000).toFixed(2)} km`} />
      </View>

      <View style={styles.buttons}>
        <Button title="Start" onPress={onStart} disabled={running} />
        <Button title="Stop" onPress={onStop} disabled={!running} />
        <Button title="Flush" onPress={() => Bg.flush().then(refreshStatus)} />
        <Button title="Clear" onPress={() => Bg.clearBuffer().then(refreshStatus)} />
      </View>

      <FlatList
        style={styles.list}
        data={fixes}
        keyExtractor={(fix, i) => `${fix.timestamp}-${i}`}
        renderItem={({ item }) => (
          <View style={styles.fix}>
            <Text style={styles.fixCoords}>
              {item.latitude.toFixed(5)}, {item.longitude.toFixed(5)}
            </Text>
            <Text style={styles.fixMeta}>
              ±{item.accuracy?.toFixed(0) ?? '?'}m · {item.isMoving ? item.activity : 'still'} ·{' '}
              {item.speed != null ? `${(item.speed * 3.6).toFixed(1)} km/h` : '—'}
            </Text>
          </View>
        )}
      />
    </SafeAreaView>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.stat}>
      <Text style={styles.statValue}>{value}</Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

function haversine(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6_371_000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, backgroundColor: '#0b0f14' },
  title: { fontSize: 20, fontWeight: '700', color: '#fff', marginBottom: 16 },
  row: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 16 },
  stat: { alignItems: 'center', flex: 1 },
  statValue: { fontSize: 16, fontWeight: '700', color: '#3DDC84' },
  statLabel: { fontSize: 12, color: '#8a94a6' },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 16 },
  list: { flex: 1 },
  fix: { paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#1c2530' },
  fixCoords: { color: '#fff', fontVariant: ['tabular-nums'] },
  fixMeta: { color: '#8a94a6', fontSize: 12 },
});
