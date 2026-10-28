<template>
  <section class="mt-nokeys" aria-labelledby="mt-nokeys-title">
    <div class="mt-nokeys__icon" aria-hidden="true"><KeyRound :size="24" /></div>
    <h3 id="mt-nokeys-title" class="mt-nokeys__title">Connect Breadboard to Mechanical Turk</h3>
    <p class="mt-nokeys__lead">
      The server doesn’t have AWS keys yet, so it can’t talk to Mechanical Turk. Whoever runs your
      Breadboard server sets this up once; the keys stay on the server and are never shown here.
    </p>

    <ol class="mt-nokeys__steps">
      <li>
        <strong>Have an MTurk requester account linked to AWS.</strong>
        Sign in at <a href="https://requester.mturk.com" target="_blank" rel="noopener noreferrer">requester.mturk.com</a>
        (and <a href="https://requestersandbox.mturk.com" target="_blank" rel="noopener noreferrer">requestersandbox.mturk.com</a> for practice)
        and link it to your AWS account under <em>Developer</em>.
      </li>
      <li>
        <strong>Create an access key in AWS</strong> for a user allowed to use Mechanical Turk
        (for example with the <code>AmazonMechanicalTurkFullAccess</code> policy).
      </li>
      <li>
        <strong>Give the keys to Breadboard</strong>, either as environment variables when it starts:
        <pre class="rc-code">AMT_ACCESS_KEY=your-access-key-id
AMT_SECRET_KEY=your-secret-access-key</pre>
        or in the server’s configuration file (<code>conf/application-prod.conf</code>):
        <pre class="rc-code">amt.accessKey = "your-access-key-id"
amt.secretKey = "your-secret-access-key"</pre>
      </li>
      <li><strong>Restart Breadboard</strong>, then check again here. The server only reads the keys when it starts.</li>
    </ol>

    <div class="mt-nokeys__actions">
      <BButton variant="primary" :icon="RotateCw" :loading="checking" @click="emit('retry')">Check again</BButton>
      <BButton
        :icon-right="ExternalLink"
        href="https://github.com/human-nature-lab/breadboard/wiki/Creating-an-AMT-Requester-Account"
        target="_blank"
        rel="noopener noreferrer"
      >
        Step-by-step guide
      </BButton>
      <BButton variant="ghost" @click="emit('prolific')">Recruit on Prolific instead</BButton>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ExternalLink, KeyRound, RotateCw } from 'lucide-vue-next'
import { BButton } from '@/ui'

defineProps<{ checking?: boolean }>()
const emit = defineEmits<{ retry: [], prolific: [] }>()
</script>

<style>
.mt-nokeys {
  max-width: 720px;
  margin: 8px auto;
  padding: 28px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}
.mt-nokeys__icon { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 14px; background: var(--accent-soft); color: var(--accent); margin-bottom: 14px; }
.mt-nokeys__title { font-size: var(--text-lg); }
.mt-nokeys__lead { margin-top: 6px; color: var(--muted); }
.mt-nokeys__steps { margin: 18px 0 0; padding-left: 20px; display: flex; flex-direction: column; gap: 12px; color: var(--text-soft); }
.mt-nokeys__steps li::marker { color: var(--faint); font-weight: 600; }
.mt-nokeys__steps strong { color: var(--text); }
.mt-nokeys__steps .rc-code { margin: 8px 0; }
.mt-nokeys__actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 22px; }
@media (max-width: 640px) { .mt-nokeys { padding: 20px 16px; } }
</style>
