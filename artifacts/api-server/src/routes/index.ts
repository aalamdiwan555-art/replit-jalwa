import { Router, type IRouter } from "express";
import healthRouter from "./health";
import deviceControlRouter from "./device-control";

const router: IRouter = Router();

router.use(healthRouter);
router.use(deviceControlRouter);

export default router;
