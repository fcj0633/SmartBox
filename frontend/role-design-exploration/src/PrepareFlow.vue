<script setup lang="ts">
import { ref } from 'vue';
import { boxes } from './model';
import type { PreviewState } from './usePreview';
import Icon from './Icon.vue';
const props = defineProps<{
    p: PreviewState;
}>();
defineEmits<{
    start: [
    ];
}>();
const step = ref(1);
const name = ref('');
function next() {
    if (props.p.chooseBox(props.p.selectedBoxId)) {
        name.value = props.p.task.item;
        step.value = 2;
    }
}
function bind() {
    if (props.p.bind(name.value))
        step.value = 3;
}
</script>
<template>
  <section class="flow-sheet">
    <ol class="stepper">
      <li v-for="(label,i) in ['选择箱体','绑定物品','核对开始']" :key="label" :class="{active:step===i+1,done:step>i+1}">
        <b>{{i+1}}</b><span>{{label}}</span></li>
    </ol>
    <template v-if="step===1">
      <h2>选择运输箱体</h2>
      <p>一个箱体同时只能执行一个运输任务（V1 暂定）。</p><button v-for="b in p.scenario==='empty'?[]:boxes" :key="b.id"
        :class="['select-box-row',{chosen:p.selectedBoxId===b.id}]" :disabled="!!p.blocked(b.id)"
        @click="p.selectedBoxId=b.id">
        <Icon name="box" /><span><strong>{{b.name}}</strong><small>{{b.id}} ·
            {{b.source==='personal'?'个人拥有':'企业分配'}}</small><small>{{p.blocked(b.id)||'可使用'}}</small></span><span
          class="selection-mark">{{p.selectedBoxId===b.id?'已选':'选择'}}</span>
      </button>
      <p v-if="p.scenario==='empty'">当前没有可用箱体。</p><button class="button primary wide"
        :disabled="!!p.blocked(p.selectedBoxId)" @click="next">下一步：绑定物品</button>
    </template>
    <form v-else-if="step===2" @submit.prevent="bind">
      <h2>绑定运输物品</h2>
      <p>{{p.box.name}} · {{p.task.id}}</p><label class="field">物品名称<input v-model="name" maxlength="50"
          placeholder="填写本次运输的物品名称" /></label>
      <p class="data-note">示例使用名称与简述；正式字段和绑定数量待确认。</p><label class="field">物品简述<textarea readonly>保鲜运输 ·
          非敏感演示物品</textarea></label>
      <p v-if="p.error" class="form-error" role="alert">{{p.error}}</p>
      <div class="form-actions"><button type="button" class="button secondary"
          @click="step=1;p.error=''">上一步</button><button class="button primary" type="submit">绑定并核对</button></div>
    </form>
    <template v-else>
      <h2>核对并开始运输</h2>
      <p class="binding-success">
        <Icon name="check" :size="18" />物品已绑定，尚未开始运输。
      </p>
      <dl class="definition-list">
        <div>
          <dt>运输编号</dt>
          <dd>{{p.task.id}}</dd>
        </div>
        <div>
          <dt>箱体</dt>
          <dd>{{p.box.name}} · {{p.task.boxId}}</dd>
        </div>
        <div>
          <dt>物品</dt>
          <dd>{{p.task.item}}</dd>
        </div>
        <div>
          <dt>使用资格</dt>
          <dd>{{p.denied(p.task.boxId)?'授权已失效':'演示有效'}}</dd>
        </div>
      </dl>
      <div class="form-actions"><button class="button secondary" @click="step=2">修改物品</button><button
          class="button primary" :disabled="!!p.blocked(p.task.boxId)" @click="$emit('start')">确认并开始运输</button></div>
    </template>
  </section>
</template>
