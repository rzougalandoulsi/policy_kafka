{{/* Names and labels */}}
{{- define "governance.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "governance.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "governance.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | quote }}
app.kubernetes.io/name: {{ include "governance.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: kafka-governance
governance.itland.com/environment: {{ .Values.environment | quote }}
{{- end -}}

{{- define "governance.selectorLabels" -}}
app.kubernetes.io/name: {{ include "governance.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "governance.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "governance.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Image references (digest wins over tag) */}}
{{- define "governance.image" -}}
{{- if .Values.image.digest -}}
{{ .Values.image.repository }}@{{ .Values.image.digest }}
{{- else -}}
{{ .Values.image.repository }}:{{ required "image.tag (or image.digest) is required: the deploy workflow sets it to the git sha" .Values.image.tag }}
{{- end -}}
{{- end -}}

{{- define "governance.opaImage" -}}
{{- if .Values.opa.image.digest -}}
{{ .Values.opa.image.repository }}@{{ .Values.opa.image.digest }}
{{- else -}}
{{ .Values.opa.image.repository }}:{{ .Values.opa.image.tag }}
{{- end -}}
{{- end -}}

{{/*
The promoted policy release: releases/<environment>.json unless release.file points elsewhere.
Validated here so a broken promotion fails `helm template`, never a pod.
*/}}
{{- define "governance.releaseFile" -}}
{{- default (printf "releases/%s.json" .Values.environment) .Values.release.file -}}
{{- end -}}

{{- define "governance.releaseJson" -}}
{{- $file := include "governance.releaseFile" . -}}
{{- $raw := .Files.Get $file -}}
{{- if not $raw -}}
{{- fail (printf "policy release file %s not found in the chart (promote a release with Workflow C first)" $file) -}}
{{- end -}}
{{- $raw -}}
{{- end -}}

{{- define "governance.release" -}}
{{- $release := include "governance.releaseJson" . | fromJson -}}
{{- if not $release.releaseId -}}{{- fail "policy release file has no releaseId" -}}{{- end -}}
{{- range $name := list "core" "manifests" "naming" "quotas" "resources" "changes" -}}
{{- $bundle := index $release.bundles $name -}}
{{- if or (not $bundle) (not $bundle.revision) -}}
{{- fail (printf "policy release %s has no revision for bundle %s" $release.releaseId $name) -}}
{{- end -}}
{{- end -}}
{{- toJson $release -}}
{{- end -}}

{{- define "governance.releaseId" -}}
{{- (include "governance.release" . | fromJson).releaseId -}}
{{- end -}}

{{/* The OPA sidecar configuration (plan section 11.3), rendered from the release file and the Artifactory values. */}}
{{- define "governance.opaConfig" -}}
{{- $release := include "governance.release" . | fromJson -}}
services:
  artifactory:
    url: {{ printf "%s/%s" (trimSuffix "/" .Values.artifactory.url) .Values.artifactory.repositoryKey | quote }}
    credentials:
      bearer:
        token: "${ARTIFACTORY_TOKEN}"
    response_header_timeout_seconds: {{ .Values.artifactory.responseHeaderTimeoutSeconds }}
    allow_insecure_tls: false
{{- if .Values.artifactory.caBundleConfigMap }}
    tls:
      ca_cert: /etc/opa/ca/ca.crt
{{- end }}
bundles:
{{- range $name := list "core" "manifests" "naming" "quotas" "resources" "changes" }}
{{- $revision := (index $release.bundles $name).revision }}
  {{ $name }}:
    service: artifactory
    resource: {{ printf "%s/%s/%s.tar.gz" $name $revision $revision | quote }}
    persist: true
    polling:
      min_delay_seconds: {{ $.Values.opa.polling.minDelaySeconds }}
      max_delay_seconds: {{ $.Values.opa.polling.maxDelaySeconds }}
    signing:
      keyid: {{ $.Values.signing.keyId | quote }}
{{- end }}
keys:
  {{ .Values.signing.keyId }}:
    algorithm: RS256
    key: |
{{ required "signing.publicKey is required: the RS256 public key Workflow B signs bundles with" .Values.signing.publicKey | indent 6 }}
{{- range $id, $pem := .Values.signing.additionalKeys }}
  {{ $id }}:
    algorithm: RS256
    key: |
{{ $pem | indent 6 }}
{{- end }}
persistence_directory: /var/opa
decision_logs:
  console: true
  reporting:
    min_delay_seconds: 5
    max_delay_seconds: 10
status:
  console: true
{{- end -}}
