# Sharing and agent toolbar

These captures render the actual common editor used by the browser, with a private design,
a fake host reporting Claude Code activity, and no provider calls. The separate Sharing and
Connect invitation strips are absent. Wide layouts keep Private/Public beside the agent control;
compact layouts show the visibility icon beside the title and offer More → Sharing.

AgentToolbarTest checks Sharing callbacks in both layouts, access notices and Sign in, named
agent activity, browser chat readiness/work, and the external activity tab. It regenerates these
captures under ui-builder/build/agent-toolbar. Direct access to ui.coo.ee was blocked in the
execution environment; these are local component captures.

| State | Wide | Compact |
| --- | --- | --- |
| Connect | [Wide](desktop-connect.png) | [Compact](compact-connect.png) |
| Reported agent | [Wide](desktop-agent.png) | [Compact](compact-agent.png) |
