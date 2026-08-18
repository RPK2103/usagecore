{{- define "usagecore.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "usagecore.fullname" -}}
{{- printf "%s" (include "usagecore.name" .) }}
{{- end }}

{{- define "usagecore.labels" -}}
app.kubernetes.io/part-of: usagecore
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- with .Values.global.labels }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{- define "usagecore.selectorLabels" -}}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "usagecore.appLabels" -}}
{{ include "usagecore.labels" . }}
{{ include "usagecore.selectorLabels" . }}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{- define "usagecore.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: 10001
fsGroup: 10001
seccompProfile:
  type: RuntimeDefault
{{- end }}

{{- define "usagecore.containerSecurityContext" -}}
allowPrivilegeEscalation: false
capabilities:
  drop:
    - ALL
readOnlyRootFilesystem: false
runAsNonRoot: true
runAsUser: 10001
{{- end }}

{{- define "usagecore.dbEnv" -}}
- name: USAGECORE_DB_URL
  valueFrom:
    configMapKeyRef:
      name: usagecore-config
      key: USAGECORE_DB_URL
- name: USAGECORE_DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: usagecore-secrets
      key: USAGECORE_DB_USERNAME
- name: USAGECORE_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: usagecore-secrets
      key: USAGECORE_DB_PASSWORD
{{- end }}

{{- define "usagecore.commonEnv" -}}
- name: USAGECORE_JWK_SET_URI
  valueFrom:
    configMapKeyRef:
      name: usagecore-config
      key: USAGECORE_JWK_SET_URI
- name: USAGECORE_OTLP_ENABLED
  valueFrom:
    configMapKeyRef:
      name: usagecore-config
      key: USAGECORE_OTLP_ENABLED
{{- end }}

{{- define "usagecore.probes" -}}
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: {{ .port }}
  periodSeconds: {{ .Values.probes.startup.periodSeconds }}
  failureThreshold: {{ .Values.probes.startup.failureThreshold }}
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: {{ .port }}
  periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
  failureThreshold: {{ .Values.probes.liveness.failureThreshold }}
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: {{ .port }}
  periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
  failureThreshold: {{ .Values.probes.readiness.failureThreshold }}
{{- end }}
