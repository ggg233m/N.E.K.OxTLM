import {
  Page,
  Card,
  Stack,
  Text,
  StatusBadge,
  KeyValue,
  Select,
  Button,
  Alert,
  Divider,
  EmptyState,
  ActionButton,
  RefreshButton,
} from "@neko/plugin-ui"
import type { HostedAction, PluginSurfaceProps } from "@neko/plugin-ui"

type MaidInfo = {
  id: string
  name: string
  health: number
  max_health: number
  is_sitting: boolean
  is_following: boolean
  owner: string
}

type State = {
  connected: boolean
  ws_url: string
  maids: MaidInfo[]
  assigned_maid_id: string
  assigned_maid_name: string
  command_execution_enabled: boolean
}

export default function Panel(props: PluginSurfaceProps<State>) {
  const { t, state, actions, useLocalState } = props

  const connected = state?.connected ?? false
  const maids = state?.maids ?? []
  const assignedId = state?.assigned_maid_id ?? ""
  const assignedName = state?.assigned_maid_name ?? ""
  const commandExecutionEnabled = state?.command_execution_enabled ?? false

  const [selectedMaidId, setSelectedMaidId] = useLocalState<string>("selectedMaidId", "")

  const assignAction = actions.find((a) => a.id === "assign_maid") as HostedAction | undefined
  const refreshAction = actions.find((a) => a.id === "refresh_maid_status") as HostedAction | undefined

  const maidOptions = [
    { value: "", label: t("maid.selectPlaceholder") },
    ...maids.map((m) => ({
      value: m.id,
      label: `${m.name} (${m.id.substring(0, 8)}...)`,
    })),
  ]

  const assignedMaid = maids.find((m) => m.id === assignedId)
  const selectedMaid = maids.find((m) => m.id === selectedMaidId)

  return (
    <Page title={t("panel.title")} subtitle={t("panel.subtitle")}>
      <Card title={t("connection.title")}>
        <Stack>
          <StatusBadge tone={connected ? "success" : "error"}>
            {connected ? t("connection.connected") : t("connection.disconnected")}
          </StatusBadge>
          <KeyValue
            items={[
              { key: t("connection.wsUrl"), value: state?.ws_url ?? "-" },
            ]}
          />
          <Stack direction="horizontal">
            {refreshAction && (
              <ActionButton action={refreshAction}>{t("actions.refresh")}</ActionButton>
            )}
          </Stack>
        </Stack>
      </Card>

      <Card title={t("command.title")}>
        <Stack>
          <Alert tone={commandExecutionEnabled ? "success" : "warning"}>
            {commandExecutionEnabled ? t("command.enabled") : t("command.disabled")}
          </Alert>
          <Text>{t("command.description")}</Text>
        </Stack>
      </Card>

      <Card title={t("maid.title")}>
        <Stack>
          {assignedId && assignedName ? (
            <Alert tone="success">{t("maid.assigned", { name: assignedName })}</Alert>
          ) : (
            <Alert tone="warning">{t("maid.notAssigned")}</Alert>
          )}

          {assignedMaid && (
            <KeyValue
              items={[
                { key: t("maid.name"), value: assignedMaid.name },
                { key: t("maid.health"), value: `${assignedMaid.health}/${assignedMaid.max_health}` },
                { key: t("maid.sitting"), value: assignedMaid.is_sitting ? t("yes") : t("no") },
                { key: t("maid.following"), value: assignedMaid.is_following ? t("yes") : t("no") },
                { key: t("maid.owner"), value: assignedMaid.owner },
              ]}
            />
          )}

          <Divider />

          {maids.length > 0 ? (
            <Stack>
              <Text>{t("maid.selectHint")}</Text>
              <Select
                options={maidOptions}
                value={selectedMaidId}
                onChange={setSelectedMaidId}
              />
              {assignAction && selectedMaidId && (
                <ActionButton
                  action={assignAction}
                  values={{ maid_id: selectedMaidId, maid_name: selectedMaid?.name ?? "" }}
                >
                  {t("actions.assignMaid")}
                </ActionButton>
              )}
            </Stack>
          ) : connected ? (
            <EmptyState title={t("maid.noMaids")} description={t("maid.noMaidsHint")} />
          ) : (
            <EmptyState title={t("maid.connectFirst")} description={t("maid.connectFirstHint")} />
          )}
        </Stack>
      </Card>
    </Page>
  )
}
