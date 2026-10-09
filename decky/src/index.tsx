import { ButtonItem, ConfirmModal, Field, PanelSection, PanelSectionRow, showModal, staticClasses } from "@decky/ui";
import { callable, definePlugin, toaster } from "@decky/api";
import { useEffect, useState } from "react";
import { FaExchangeAlt } from "react-icons/fa";

const getStatus = callable<[], string>("get_status");
const rebootToAndroid = callable<[], { ok: boolean; error?: string }>("reboot_to_android");

const SOURCE: Record<string, string> = { sd: " · SD card", internal: " · Internal", usb: " · USB", auto: " · Auto" };

function describe(status: string): string {
  const [mode, source] = status.split(" ");
  if (mode === "android") return "Android";
  if (mode === "linux") return "Linux" + (SOURCE[source] ?? "");
  return "Unknown";
}

function Content() {
  const [status, setStatus] = useState("…");

  useEffect(() => {
    getStatus().then(setStatus).catch(() => setStatus("unknown"));
  }, []);

  const confirm = () =>
    showModal(
      <ConfirmModal
        strTitle="Reboot to Android?"
        strDescription="Sets Android as the default boot target and restarts now. Use the Boot Switch app or tile on Android to come back."
        strOKButtonText="Reboot"
        onOK={async () => {
          const result = await rebootToAndroid();
          if (!result.ok) toaster.toast({ title: "Boot Switch", body: result.error ?? "Failed - nothing changed" });
        }}
      />,
    );

  return (
    <PanelSection>
      <PanelSectionRow>
        <Field label="Default boot">{describe(status)}</Field>
      </PanelSectionRow>
      <PanelSectionRow>
        <ButtonItem layout="below" onClick={confirm}>
          Reboot to Android
        </ButtonItem>
      </PanelSectionRow>
    </PanelSection>
  );
}

export default definePlugin(() => ({
  name: "Boot Switch",
  titleView: <div className={staticClasses.Title}>Boot Switch</div>,
  content: <Content />,
  icon: <FaExchangeAlt />,
}));
