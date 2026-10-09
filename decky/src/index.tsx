import { ButtonItem, ConfirmModal, PanelSection, PanelSectionRow, showModal, staticClasses } from "@decky/ui";
import { callable, definePlugin, toaster } from "@decky/api";
import { useEffect, useState } from "react";
import { FaExchangeAlt } from "react-icons/fa";

type Target = { location: string; name: string };
type Info = { mode?: string; source?: string; targets?: Target[]; error?: string };

const getInfo = callable<[], Info>("get_info");
const restartInto = callable<[target: string, location: string], { ok: boolean; error?: string }>("restart_into");

const WHERE: Record<string, string> = { internal: "Internal storage", sd: "SD card", usb: "USB drive" };
const title = (t: Target) => (t.location === "usb" ? "Linux on USB" : t.name);

function confirmRestart(label: string, detail: string, target: string, location = "") {
  showModal(
    <ConfirmModal
      strTitle={`Restart into ${label}?`}
      strDescription={detail}
      strOKButtonText="Restart"
      onOK={async () => {
        const result = await restartInto(target, location);
        if (!result.ok) toaster.toast({ title: "ABL Dual Boot", body: result.error ?? "Failed - nothing changed" });
      }}
    />,
  );
}

function Content() {
  const [info, setInfo] = useState<Info | null>(null);

  useEffect(() => {
    getInfo().then(setInfo).catch((e) => setInfo({ error: String(e) }));
  }, []);

  if (!info) return <PanelSection><PanelSectionRow>Loading…</PanelSectionRow></PanelSection>;
  if (info.error) return <PanelSection><PanelSectionRow>{info.error}</PanelSectionRow></PanelSection>;

  // Other Linux systems: everything except the one this device boots now.
  const others = (info.targets ?? []).filter((t) => !(info.mode === "linux" && t.location === info.source));

  return (
    <PanelSection title="Restart into">
      <PanelSectionRow>
        <ButtonItem
          layout="below"
          description="Keeps starting Android until you switch back from the ABL Dual Boot app."
          onClick={() => confirmRestart("Android", "Your device restarts now and keeps starting Android until you switch back.", "android")}
        >
          Android
        </ButtonItem>
      </PanelSectionRow>
      {others.map((t) => (
        <PanelSectionRow key={t.location}>
          <ButtonItem
            layout="below"
            description={t.location === "usb" ? "USB drive · plug in before restarting" : WHERE[t.location]}
            onClick={() =>
              confirmRestart(
                title(t),
                (t.location === "usb" ? "Plug in the USB drive first. " : "") +
                  `Your device restarts now and keeps starting ${title(t)} until you switch again.`,
                "linux",
                t.location,
              )
            }
          >
            {title(t)}
          </ButtonItem>
        </PanelSectionRow>
      ))}
    </PanelSection>
  );
}

export default definePlugin(() => ({
  name: "ABL Dual Boot",
  titleView: <div className={staticClasses.Title}>ABL Dual Boot</div>,
  content: <Content />,
  icon: <FaExchangeAlt />,
}));
