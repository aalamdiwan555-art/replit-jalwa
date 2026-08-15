import { createInsertSchema } from "drizzle-zod";
import { boolean, integer, pgTable, serial, text, timestamp } from "drizzle-orm/pg-core";
import { z } from "zod/v4";

export const devicesTable = pgTable("devices", {
  id: text("id").primaryKey(),
  displayName: text("display_name").notNull(),
  model: text("model").notNull(),
  androidVersion: text("android_version").notNull(),
  appVersion: text("app_version").notNull(),
  status: text("status").notNull().default("PENDING"),
  approved: boolean("approved").notNull().default(false),
  paused: boolean("paused").notNull().default(false),
  batteryLevel: integer("battery_level"),
  activeAction: text("active_action"),
  deviceToken: text("device_token").notNull().unique(),
  lastSeenAt: timestamp("last_seen_at", { withTimezone: true }),
  enrolledAt: timestamp("enrolled_at", { withTimezone: true }).notNull().defaultNow(),
  updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow().$onUpdate(() => new Date()),
});

export const deviceCommandsTable = pgTable("device_commands", {
  id: text("id").primaryKey(),
  deviceId: text("device_id").notNull(),
  type: text("type").notNull(),
  action: text("action").notNull().default("NONE"),
  templateName: text("template_name"),
  status: text("status").notNull().default("QUEUED"),
  createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
  acknowledgedAt: timestamp("acknowledged_at", { withTimezone: true }),
});

export const activityEventsTable = pgTable("activity_events", {
  id: serial("id").primaryKey(),
  type: text("type").notNull(),
  message: text("message").notNull(),
  deviceId: text("device_id"),
  deviceName: text("device_name"),
  createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
});

export const insertDeviceSchema = createInsertSchema(devicesTable).omit({
  enrolledAt: true,
  updatedAt: true,
});
export const insertDeviceCommandSchema = createInsertSchema(deviceCommandsTable).omit({
  createdAt: true,
});
export const insertActivityEventSchema = createInsertSchema(activityEventsTable).omit({
  id: true,
  createdAt: true,
});

export type Device = typeof devicesTable.$inferSelect;
export type DeviceCommand = typeof deviceCommandsTable.$inferSelect;
export type ActivityEvent = typeof activityEventsTable.$inferSelect;
export type InsertDevice = z.infer<typeof insertDeviceSchema>;
export type InsertDeviceCommand = z.infer<typeof insertDeviceCommandSchema>;
export type InsertActivityEvent = z.infer<typeof insertActivityEventSchema>;