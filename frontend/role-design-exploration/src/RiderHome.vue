<script setup lang="ts">
import { boxes } from './model';
import type { PreviewState } from './usePreview';
import Environment from './Environment.vue';
import Icon from './Icon.vue';
defineProps<{
    p: PreviewState;
}>();
</script>
<template>
  <div class="workspace-grid">
    <section class="current-work">
      <template v-if="p.activeTasks.length">
        <div class="section-heading">
          <h2>当前运输<span v-if="p.activeTasks.length>1">（{{p.activeTasks.length}}）</span></h2><span
            class="status-live">运输中</span>
        </div>
        <article v-for="(t,i) in p.activeTasks" :key="t.id" class="current-transport">
          <div class="transport-identity">
            <h3>{{t.item}}</h3>
            <p>{{t.boxId}} · {{t.start}} 开始</p>
          </div>
          <div v-if="p.scenario==='alarm'&&i===0" class="attention">
            <Icon name="alert" />
            <div><strong>温度异常，请关注当前运输</strong><span>14:26 · 有效样本 8.6 °C</span></div><button class="text-button"
              @click="p.openTask(t.id,'alerts')">查看异常</button>
          </div>
          <Environment :temperature="t.temperature" :humidity="t.humidity" :scenario="i===0?p.scenario:'normal'" />
          <button class="button primary wide" @click="p.openTask(t.id)">查看当前运输
            <Icon name="chevron" :size="18" />
          </button>
        </article>
      </template>
      <div v-else class="no-transport">
        <Icon name="task" :size="32" />
        <h2>{{p.scenario==='empty'?'暂无可使用的箱体':'当前没有运输'}}</h2>
        <p>{{p.scenario==='empty'?'请确认个人箱体归属，或联系企业分配箱体。':'选择可用箱体，绑定物品后开始运输。'}}</p><button v-if="p.scenario!=='empty'"
          class="button primary" @click="p.prepare()">开始新运输</button><button v-else class="button secondary"
          @click="p.go('account')">查看使用权限</button>
      </div>
    </section>
    <aside class="workspace-side">
      <section class="box-list-section">
        <div class="section-heading">
          <h2>我的箱体</h2><button class="text-button" @click="p.go('boxes')">全部</button>
        </div><template v-if="p.scenario!=='empty'"><button v-for="b in boxes" :key="b.id" class="list-row box-row"
            @click="p.openBox(b.id)">
            <Icon name="box" /><span><strong>{{b.name}}</strong><small>{{b.id}} ·
                {{b.source==='personal'?'个人拥有':'企业分配'}}</small><small>{{p.scenario==='offline'?'ENV 离线':'节点在线'}} ·
                {{p.blocked(b.id)||'可使用'}}</small></span>
            <Icon name="chevron" :size="18" />
          </button><button v-if="p.activeTasks.length" class="button secondary wide"
            @click="p.prepare()">开始新运输</button></template>
        <p v-else>暂无箱体，不能自行输入箱号获取权限。</p>
      </section>
      <section class="auxiliary"><button class="list-row" @click="p.go('history')">
          <Icon name="archive" /><strong>运输记录</strong>
          <Icon name="chevron" :size="18" />
        </button><button class="list-row"
          @click="p.openTask(p.activeTasks[0]?.id||p.tasks.find(t=>t.state==='completed')!.id,'alerts')">
          <Icon name="bell" /><strong>异常记录</strong>
          <Icon name="chevron" :size="18" />
        </button></section>
    </aside>
  </div>
</template>
