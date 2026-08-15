import { randomUUID } from "node:crypto";
import { Router, type IRouter } from "express";
import { and, desc, eq } from "drizzle-orm";
import {
  AdminSummary,
  DeviceHeartbeatBody,
  DeviceHeartbeatHeader,
  DeviceHeartbeatParams,
  DeviceHeartbeatResponse,
  EnrollDeviceBody,
  EnrollDeviceResponse,
  GetActivityQueryParams,
  GetActivityResponse,
  GetAdminSummaryResponse,
  GetDeviceParams,
  GetDeviceResponse,
  ListDeviceCommandsResponseItem,
  ListDeviceCommandsParams,
  ListDeviceCommandsResponse,
  ListDevicesQueryParams,
  ListDevicesResponseItem,
  ListDevicesResponse,
  SendDeviceCommandBody,
  SendDeviceCommandParams,
  SendDeviceCommandResponse,
  UpdateDeviceBody,
  UpdateDeviceParams,
} from "@workspace/api-zod";
import { db, activityEventsTable, deviceCommandsTable, devicesTable } from "@workspace/db";
import type { Device, DeviceCommand, ActivityEvent } from "@workspace/db";

const router: IRouter = Router();
const onlineWindowMs = 90_000;
let seedPromise: Promise<void> | null = null;

const now = () => new Date();

function deviceStatus(device: Device): "ONLINE" | "OFFLINE" | "PAUSED" | "PENDING" {
  if (!device.approved) return "PENDING";
  if (device.paused) return "PAUSED";
  if (device.lastSeenAt && now().getTime() - device.lastSeenAt.getTime() <= onlineWindowMs) {
    return "ONLINE";
  }
  return "OFFLINE";
}

function toApiDevice(device: Device) {
  return ListDevicesResponseItem.parse({
    id: device.id,
    displayName: device.displayName,
    model: device.model,
    androidVersion: device.androidVersion,
    appVersion: device.appVersion,
    status: deviceStatus(device),
    approved: device.approved,
    batteryLevel: device.batteryLevel,
    activeAction: device.activeAction,
    lastSeenAt: device.lastSeenAt?.toISOString() ?? null,
    enrolledAt: device.enrolledAt.toISOString(),
    updatedAt: device.updatedAt.toISOString(),
  });
}

function toApiCommand(command: DeviceCommand) {
  return ListDeviceCommandsResponseItem.parse({
    id: command.id,
    deviceId: command.deviceId,
    type: command.type,
    action: command.action,
    templateName: command.templateName,
    status: command.status,
    createdAt: command.createdAt.toISOString(),
    acknowledgedAt: command.acknowledgedAt?.toISOString() ?? null,
  });
}

function toApiActivity(event: ActivityEvent) {
  return {
    id: event.id,
    type: event.type,
    message: event.message,
    deviceId: event.deviceId,
    deviceName: event.deviceName,
    createdAt: event.createdAt.toISOString(),
  };
}

async function recordActivity(
  type: string,
  message: string,
  device?: Pick<Device, "id" | "displayName">,
) {
  await db.insert(activityEventsTable).values({
    type,
    message,
    deviceId: device?.id ?? null,
    deviceName: device?.displayName ?? null,
  });
}

async function ensureSeedData() {
  if (!seedPromise) {
    seedPromise = (async () => {
      const existing = await db.select({ id: devicesTable.id }).from(devicesTable).limit(1);
      if (existing.length > 0) return;

      const enrolledAt = new Date(Date.now() - 1000 * 60 * 38);
      await db.insert(devicesTable).values([
        {
          id: "demo-pixel-8",
          displayName: "Pixel 8 · Lab A",
          model: "Google Pixel 8",
          androidVersion: "Android 15",
          appVersion: "1.0.0",
          approved: true,
          paused: false,
          batteryLevel: 82,
          activeAction: "NONE",
          deviceToken: randomUUID(),
          lastSeenAt: new Date(Date.now() - 18_000),
          enrolledAt,
          updatedAt: new Date(Date.now() - 18_000),
        },
        {
          id: "demo-galaxy-s24",
          displayName: "Galaxy S24 · Lab B",
          model: "Samsung SM-S921B",
          androidVersion: "Android 14",
          appVersion: "1.0.0",
          approved: true,
          paused: true,
          batteryLevel: 46,
          activeAction: "NONE",
          deviceToken: randomUUID(),
          lastSeenAt: new Date(Date.now() - 1000 * 60 * 7),
          enrolledAt: new Date(Date.now() - 1000 * 60 * 60 * 6),
          updatedAt: new Date(Date.now() - 1000 * 60 * 7),
        },
        {
          id: "demo-nothing-phone",
          displayName: "Nothing Phone · Intake",
          model: "Nothing A142",
          androidVersion: "Android 14",
          appVersion: "1.0.0",
          approved: false,
          paused: false,
          batteryLevel: 67,
          activeAction: "NONE",
          deviceToken: randomUUID(),
          lastSeenAt: new Date(Date.now() - 1000 * 60 * 4),
          enrolledAt: new Date(Date.now() - 1000 * 60 * 9),
          updatedAt: new Date(Date.now() - 1000 * 60 * 4),
        },
      ]);
      await recordActivity("DEVICE_ENROLLED", "3 devices are ready for review");
    })().catch((error) => {
      seedPromise = null;
      throw error;
    });
  }
  await seedPromise;
}

async function findDevice(id: string) {
  const result = await db.select().from(devicesTable).where(eq(devicesTable.id, id)).limit(1);
  return result[0];
}

router.get("/admin/summary", async (req, res) => {
  try {
    await ensureSeedData();
    const devices = await db.select().from(devicesTable);
    const commandsToday = await db
      .select()
      .from(deviceCommandsTable)
      .where(and(
        // Keep the query portable while limiting the result set to this small admin surface.
        eq(deviceCommandsTable.status, "QUEUED"),
      ));
    const lastSyncAt = devices
      .map((device) => device.lastSeenAt)
      .filter((date): date is Date => Boolean(date))
      .sort((a, b) => b.getTime() - a.getTime())[0];
    return res.json(GetAdminSummaryResponse.parse({
      totalDevices: devices.length,
      onlineDevices: devices.filter((device) => deviceStatus(device) === "ONLINE").length,
      pendingDevices: devices.filter((device) => deviceStatus(device) === "PENDING").length,
      pausedDevices: devices.filter((device) => deviceStatus(device) === "PAUSED").length,
      commandsToday: commandsToday.length,
      lastSyncAt: lastSyncAt?.toISOString() ?? null,
    }));
  } catch (error) {
    req.log.error({ err: error }, "Failed to build admin summary");
    return res.status(500).json({ error: "Unable to load dashboard summary" });
  }
});

router.get("/activity", async (req, res) => {
  try {
    await ensureSeedData();
    const query = GetActivityQueryParams.parse(req.query);
    const events = await db.select().from(activityEventsTable)
      .orderBy(desc(activityEventsTable.createdAt))
      .limit(query.limit ?? 12);
    return res.json(GetActivityResponse.parse(events.map(toApiActivity)));
  } catch (error) {
    req.log.error({ err: error }, "Failed to load activity");
    return res.status(500).json({ error: "Unable to load activity" });
  }
});

router.get("/devices", async (req, res) => {
  try {
    await ensureSeedData();
    const query = ListDevicesQueryParams.parse(req.query);
    const search = query.search?.trim().toLowerCase();
    const devices = await db.select().from(devicesTable).orderBy(desc(devicesTable.updatedAt));
    const filtered = devices
      .map(toApiDevice)
      .filter((device) => query.status === "ALL" || device.status === query.status)
      .filter((device) => !search || `${device.displayName} ${device.model} ${device.id}`.toLowerCase().includes(search));
    return res.json(ListDevicesResponse.parse(filtered));
  } catch (error) {
    req.log.error({ err: error }, "Failed to list devices");
    return res.status(500).json({ error: "Unable to load devices" });
  }
});

router.post("/devices", async (req, res) => {
  try {
    const input = EnrollDeviceBody.parse(req.body);
    const existing = await findDevice(input.id);
    if (existing) return res.status(409).json({ error: "Device is already enrolled" });

    const deviceToken = randomUUID();
    const [device] = await db.insert(devicesTable).values({
      ...input,
      deviceToken,
      activeAction: "NONE",
      approved: false,
      paused: false,
    }).returning();
    await recordActivity("DEVICE_ENROLLED", `${device.displayName} joined the intake queue`, device);
    return res.status(201).json(EnrollDeviceResponse.parse({
      device: toApiDevice(device),
      deviceToken,
    }));
  } catch (error) {
    req.log.error({ err: error }, "Failed to enroll device");
    return res.status(400).json({ error: "Unable to enroll device" });
  }
});

router.get("/devices/:deviceId", async (req, res) => {
  try {
    const params = GetDeviceParams.parse(req.params);
    const device = await findDevice(params.deviceId);
    if (!device) return res.status(404).json({ error: "Device not found" });
    return res.json(GetDeviceResponse.parse(toApiDevice(device)));
  } catch (error) {
    req.log.error({ err: error }, "Failed to load device");
    return res.status(400).json({ error: "Unable to load device" });
  }
});

router.patch("/devices/:deviceId", async (req, res) => {
  try {
    const params = UpdateDeviceParams.parse(req.params);
    const input = UpdateDeviceBody.parse(req.body);
    const existing = await findDevice(params.deviceId);
    if (!existing) return res.status(404).json({ error: "Device not found" });

    const [device] = await db.update(devicesTable)
      .set({
        displayName: input.displayName ?? existing.displayName,
        approved: input.approved ?? existing.approved,
        paused: input.paused ?? existing.paused,
        updatedAt: now(),
      })
      .where(eq(devicesTable.id, params.deviceId))
      .returning();
    const activityType = input.approved === false ? "DEVICE_DISABLED" : input.approved === true ? "DEVICE_APPROVED" : undefined;
    if (activityType) await recordActivity(activityType, `${device.displayName} was ${input.approved ? "approved" : "disabled"}`, device);
    return res.json(GetDeviceResponse.parse(toApiDevice(device)));
  } catch (error) {
    req.log.error({ err: error }, "Failed to update device");
    return res.status(400).json({ error: "Unable to update device" });
  }
});

router.get("/devices/:deviceId/commands", async (req, res) => {
  try {
    const params = ListDeviceCommandsParams.parse(req.params);
    const commands = await db.select().from(deviceCommandsTable)
      .where(eq(deviceCommandsTable.deviceId, params.deviceId))
      .orderBy(desc(deviceCommandsTable.createdAt))
      .limit(25);
    return res.json(ListDeviceCommandsResponse.parse(commands.map(toApiCommand)));
  } catch (error) {
    req.log.error({ err: error }, "Failed to list device commands");
    return res.status(400).json({ error: "Unable to load device commands" });
  }
});

router.post("/devices/:deviceId/commands", async (req, res) => {
  try {
    const params = SendDeviceCommandParams.parse(req.params);
    const input = SendDeviceCommandBody.parse(req.body);
    const device = await findDevice(params.deviceId);
    if (!device) return res.status(404).json({ error: "Device not found" });
    if (!device.approved) return res.status(403).json({ error: "Approve the device before sending commands" });

    const [command] = await db.insert(deviceCommandsTable).values({
      id: randomUUID(),
      deviceId: device.id,
      type: input.type,
      action: input.action ?? "NONE",
      templateName: input.templateName ?? null,
      status: "QUEUED",
    }).returning();
    await recordActivity("COMMAND_SENT", `${input.type.replaceAll("_", " ").toLowerCase()} queued for ${device.displayName}`, device);
    return res.status(201).json(SendDeviceCommandResponse.parse(toApiCommand(command)));
  } catch (error) {
    req.log.error({ err: error }, "Failed to send device command");
    return res.status(400).json({ error: "Unable to send device command" });
  }
});

router.post("/device-agent/:deviceId/heartbeat", async (req, res) => {
  try {
    const params = DeviceHeartbeatParams.parse(req.params);
    const header = DeviceHeartbeatHeader.parse(req.headers);
    const input = DeviceHeartbeatBody.parse(req.body);
    const device = await findDevice(params.deviceId);
    if (!device) return res.status(404).json({ error: "Device not found" });
    if (header["X-Device-Token"] !== device.deviceToken) return res.status(401).json({ error: "Invalid device token" });

    const [updated] = await db.update(devicesTable).set({
      model: input.model,
      androidVersion: input.androidVersion,
      appVersion: input.appVersion,
      batteryLevel: input.batteryLevel,
      activeAction: input.activeAction,
      lastSeenAt: now(),
      updatedAt: now(),
    }).where(eq(devicesTable.id, device.id)).returning();

    const pending = await db.select().from(deviceCommandsTable)
      .where(and(eq(deviceCommandsTable.deviceId, device.id), eq(deviceCommandsTable.status, "QUEUED")))
      .orderBy(deviceCommandsTable.createdAt);
    if (pending.length > 0) {
      await db.update(deviceCommandsTable)
        .set({ status: "DELIVERED" })
        .where(and(eq(deviceCommandsTable.deviceId, device.id), eq(deviceCommandsTable.status, "QUEUED")));
    }
    return res.json(DeviceHeartbeatResponse.parse({
      device: toApiDevice(updated),
      commands: pending.map((command) => toApiCommand({ ...command, status: "DELIVERED" })),
    }));
  } catch (error) {
    req.log.error({ err: error }, "Failed to process device heartbeat");
    return res.status(400).json({ error: "Unable to process heartbeat" });
  }
});

export default router;