const manifest = {"name":"Boot Switch"};
const internalAPIConnection = window.__DECKY_SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED_deckyLoaderAPIInit;
let api;
try { api = internalAPIConnection.connect(2, manifest.name); } catch { api = internalAPIConnection.connect(1, manifest.name); }
const call = api.call;
const toaster = api.toaster;

const SOURCE = { sd: " · SD card", internal: " · Internal", other: "" };
const label = (t) => { const [mode, src] = (t || "unknown").split(" "); return mode === "android" ? "Android" : mode === "linux" ? "Linux" + (SOURCE[src] || "") : "Unknown"; };

function Content() {
    const [target, setTarget] = SP_REACT.useState("unknown");
    SP_REACT.useEffect(() => { call("get_target").then(setTarget).catch(() => {}); }, []);

    const confirmReboot = () => DFL.showModal(SP_JSX.jsx(DFL.ConfirmModal, {
        strTitle: "Reboot to Android?",
        strDescription: "Sets LineageOS as the default boot target and restarts now. Use the Boot Switch app or tile on Android to come back (or hold VOL- at power-on).",
        strOKButtonText: "Reboot",
        onOK: async () => {
            const r = await call("reboot_to", "android");
            if (!r.ok) toaster.toast({ title: "Boot Switch", body: r.error || "Failed - nothing changed" });
        },
    }));

    return SP_JSX.jsxs(DFL.PanelSection, { children: [
        SP_JSX.jsx(DFL.PanelSectionRow, { children:
            SP_JSX.jsx(DFL.Field, { label: "Default boot", children: label(target) }) }),
        SP_JSX.jsx(DFL.PanelSectionRow, { children:
            SP_JSX.jsx(DFL.ButtonItem, { layout: "below", onClick: confirmReboot, children: "Reboot to Android" }) }),
    ] });
}

var index = (() => ({
    name: "Boot Switch",
    titleView: SP_JSX.jsx("div", { className: DFL.staticClasses.Title, children: "Boot Switch" }),
    content: SP_JSX.jsx(Content, {}),
    icon: SP_JSX.jsxs("svg", { xmlns: "http://www.w3.org/2000/svg", width: "24", height: "24", viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: "2", strokeLinecap: "round", strokeLinejoin: "round", children: [
        SP_JSX.jsx("path", { d: "M12 2v10" }), SP_JSX.jsx("path", { d: "M18.4 6.6a9 9 0 1 1-12.77.04" }) ] }),
}));

export { index as default };
