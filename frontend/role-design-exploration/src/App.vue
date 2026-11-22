<script setup lang="ts">
import { reactive, ref, watch, onUnmounted, nextTick } from 'vue';
import { usePreview } from './usePreview';
import { boxes, demoCode, demoDate, pages, scenarioOptions, stateNames, type Page } from './model';
import Icon from './Icon.vue';
import Environment from './Environment.vue';
import Trend from './Trend.vue';
import RiderHome from './RiderHome.vue';
import TransportOverview from './TransportOverview.vue';
import PrepareFlow from './PrepareFlow.vue';
const p = reactive(usePreview());
const review = ref(new URLSearchParams(location.search).get('presentation') !== '1');
const dialog = ref<'start' | 'finish' | 'alert' | null>(null);
const dialogElement = ref<HTMLElement | null>(null);
let previousFocus: HTMLElement | null = null;
const nav = [{
        id: 'home' as Page,
        label: '工作台',
        icon: 'task'
    }, {
        id: 'boxes' as Page,
        label: '我的箱体',
        icon: 'box'
    }, {
        id: 'history' as Page,
        label: '运输记录',
        icon: 'archive'
    }, {
        id: 'account' as Page,
        label: '我的',
        icon: 'user'
    }];
const historyFilter = ref('全部');
function reviewPage() {
    if (p.page === 'expired') {
        p.entered = false;
    }
    else
        p.go(p.page);
}
function changeRole() {
    p.scenario = 'normal';
    p.reset();
}
function exit() {
    p.entered = false;
    p.go('entry');
}
function confirm() {
    const ok = dialog.value === 'start' ? p.start() : p.finish();
    if (ok)
        dialog.value = null;
}
watch(dialog, async (value) => {
    if (value) {
        previousFocus = document.activeElement as HTMLElement;
        await nextTick();
        dialogElement.value?.querySelector<HTMLButtonElement>('button')?.focus();
    }
    else
        previousFocus?.focus();
});
function keys(e: KeyboardEvent) {
    if (e.key === 'Escape')
        dialog.value = null;
    if (e.key === 'Tab' && dialog.value) {
        const controls = dialogElement.value?.querySelectorAll<HTMLElement>('button:not(:disabled)');
        if (!controls?.length)
            return;
        const first = controls[0], last = controls[controls.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        }
        else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }
}
window.addEventListener('keydown', keys);
onUnmounted(() => window.removeEventListener('keydown', keys));
watch(() => [p.role, p.page, p.scenario, p.selectedTaskId], () => {
    const q = new URLSearchParams({
        role: p.role,
        page: p.page,
        scenario: p.scenario,
        task: p.selectedTaskId,
        presentation: review.value ? '0' : '1'
    });
    history.replaceState(null, '', '?' + q);
    dialog.value = null;
    document.title = 'SmartBox · ' + (p.role === 'rider' ? '运输工作台' : '运输查看');
});
</script>
<template>
  <div :class="['preview-app',{'rider-app':p.role==='rider'}]">
    <div v-if="review" class="review-bar" aria-label="设计评审工具"><strong>SmartBox · 交互评审</strong><label>用户端<select
          v-model="p.role" aria-label="用户端" @change="changeRole">
          <option value="rider">骑手 / 个人拥有者</option>
          <option value="customer">普通运输用户</option>
        </select></label><label>页面<select v-model="p.page" aria-label="页面" @change="reviewPage">
          <option v-for="item in pages.filter(x=>x.roles.includes(p.role))" :key="item.id" :value="item.id">
            {{item.label}}</option>
        </select></label><label>演示场景<select v-model="p.scenario" aria-label="演示场景" @change="p.applyScenario">
          <option
            v-for="s in scenarioOptions.filter(s=>p.role==='rider'?!['verify-error'].includes(s.id):!['idle','multiple','empty','revoked'].includes(s.id))"
            :key="s.id" :value="s.id">{{s.label}}</option>
        </select></label><button @click="p.scenario='normal';p.reset()">重置演示</button><button
        @click="review=false">收起评审工具</button></div>
    <header class="product-header"><a href="#" class="brand" @click.prevent="p.go('home')"><span class="brand-symbol">
          <Icon name="box" :size="24" />
        </span><strong>SmartBox</strong><span class="brand-context">{{p.role==='rider'?'个人运输':'运输查看'}}</span></a>
      <nav v-if="p.role==='rider'&&p.entered&&p.page!=='entry'" class="desktop-nav" aria-label="主要导航"><button
          v-for="n in nav" :key="n.id" :class="{current:p.page===n.id}" @click="p.go(n.id)">{{n.label}}</button></nav>
      <button v-if="p.role==='customer'&&p.entered" class="text-button" @click="exit">退出查看</button><span
        v-else-if="p.role==='rider'" class="avatar">林</span>
      <Icon v-else name="lock" :size="19" />
    </header>
    <div class="demo-strip">Demo / Mock · 数据与操作均为演示，未连接真实服务<button v-if="!review" @click="review=true"
        aria-label="展开评审工具">评审</button></div>
    <main class="main-content">
      <template v-if="p.page==='expired'">
        <section class="access-state">
          <Icon name="lock" :size="36" />
          <h1>访问授权已失效</h1>
          <p>已停止展示运输数据。请重新核对运输编号和验证码。</p><button class="button primary"
            @click="p.scenario='normal';p.error='';p.go('entry')">重新验证</button>
          <p class="data-note">真实授权有效期与撤销服务为 Planned。</p>
        </section>
      </template>
      <template v-else-if="p.page==='entry'||!p.entered">
        <section class="entry-layout">
          <div class="entry-intro">
            <Icon name="box" :size="40" />
            <h1>{{p.role==='customer'?'查看运输进度与环境':'个人运输工作台'}}</h1>
            <p>{{p.role==='customer'?'输入运输编号和验证码，查看对应物品的运输状态、环境与异常记录。':'查看当前运输、核对箱体状态，开始和结束运输。'}}</p>
            <div class="entry-points"><span>
                <Icon name="task" />运输状态
              </span><span>
                <Icon name="leaf" />环境记录
              </span><span>
                <Icon name="shield" />任务隔离
              </span></div>
          </div>
          <section class="entry-form">
            <h2>{{p.role==='customer'?'验证运输信息':'进入演示工作台'}}</h2>
            <form v-if="p.role==='customer'" @submit.prevent="p.verify"><label class="field">运输编号<input
                  v-model="p.transportNo" autocomplete="off" placeholder="输入运输编号" /></label><label
                class="field">验证码<input v-model="p.verificationCode" autocomplete="off" placeholder="输入验证码" /></label>
              <p v-if="p.error" role="alert" class="form-error">{{p.error}}</p><button type="submit"
                class="button primary wide">查看运输</button>
            </form><template v-else>
              <p>林沐 · 示例骑手 / 个人箱体拥有者</p><button class="button primary wide"
                @click="p.entered=true;p.go('home')">进入工作台</button>
            </template>
            <details v-if="p.role==='customer'" class="demo-help" open>
              <summary>公开演示信息</summary>
              <p>运输中：DEMO-1123-018<br />已结束：DEMO-1123-006<br />验证码：{{demoCode}}</p>
              <p class="data-note">公开模拟值，不是生产凭据。正式签发、限流和撤销为 Planned。</p>
            </details>
            <p v-else class="data-note">无需真实账号；示例身份仅用于本地预览。</p>
          </section>
        </section>
      </template>
      <template v-else>
        <button
          v-if="p.role==='customer'&&p.page!=='home'||p.role==='rider'&&!['home','boxes','history','account'].includes(p.page)"
          class="back-link"
          @click="p.go(p.role==='customer'?'home':p.page==='box'?'boxes':p.page==='environment'||p.page==='alerts'?'task':'home')">
          <Icon name="chevron" :size="16" />{{p.role==='customer'?'返回运输概览':'返回'}}
        </button>
        <div v-if="!(p.role==='customer'&&['home','task'].includes(p.page))&&p.page!=='task'" class="page-title">
          <h1>
            {{p.page==='home'?'运输工作台':p.page==='prepare'?'开始新运输':p.page==='account'?'我的':pages.find(x=>x.id===p.page)?.label}}
          </h1><span v-if="p.page==='home'">{{demoDate}} · 林沐</span>
        </div>
        <RiderHome v-if="p.role==='rider'&&p.page==='home'" :p="p" />
        <TransportOverview v-else-if="p.page==='task'||p.role==='customer'&&p.page==='home'" :p="p"
          @finish="dialog='finish'" />
        <PrepareFlow v-else-if="p.page==='prepare'&&p.role==='rider'" :key="p.page+p.scenario" :p="p"
          @start="dialog='start'" />
        <section v-else-if="p.page==='boxes'&&p.role==='rider'" class="sheet">
          <p>个人拥有与企业分配的箱体，使用资格分别核对。</p><button v-for="b in p.scenario==='empty'?[]:boxes" :key="b.id"
            class="list-row box-row" @click="p.openBox(b.id)">
            <Icon name="box" /><span><strong>{{b.name}}</strong><small>{{b.id}} ·
                {{b.source==='personal'?'个人拥有':'企业分配'}}</small><small>{{p.scenario==='offline'?'ENV 离线':'节点在线'}} ·
                {{p.blocked(b.id)||'可使用'}}</small></span>
            <Icon name="chevron" :size="18" />
          </button>
          <p v-if="p.scenario==='empty'">暂无可用箱体，请确认归属或联系企业分配。</p>
        </section>
        <div v-else-if="p.page==='box'&&p.role==='rider'" class="box-detail-grid">
          <section class="sheet">
            <h2>{{p.box.name}}</h2>
            <p>{{p.box.id}} · {{p.box.source==='personal'?'个人拥有':'企业分配'}}</p>
            <p v-if="p.denied(p.box.id)" class="form-error">企业分配已失效，不能绑定、开始或结束运输。</p>
            <dl class="definition-list">
              <div>
                <dt>使用状态</dt>
                <dd>{{p.blocked(p.box.id)||'可使用'}}</dd>
              </div>
              <div>
                <dt>环境画像</dt>
                <dd>{{p.box.profile}}</dd>
              </div>
            </dl><button v-if="p.tasks.some(t=>t.boxId===p.box.id&&t.state==='active')" class="button primary wide"
              @click="p.openTask(p.tasks.find(t=>t.boxId===p.box.id&&t.state==='active')!.id)">查看当前运输</button><button
              v-else class="button primary wide" :disabled="!!p.blocked(p.box.id)"
              @click="p.prepare(p.box.id)">开始新运输</button>
            <details class="node-details">
              <summary>设备节点与权限</summary>
              <p>ENV 环境节点：{{p.scenario==='offline'?'离线':'在线'}}；POSE 姿态节点：在线。</p>
              <p>门状态 UNKNOWN。电量与信号未接入。个人拥有不自动授予配置或控制权限。</p>
              <p>设备控制与布撤防接口为 Planned。</p>
            </details>
          </section>
          <section class="sheet">
            <Environment :temperature="p.box.temp" :humidity="p.box.humidity" :scenario="p.scenario" />
          </section>
        </div>
        <section v-else-if="p.page==='history'&&p.role==='rider'" class="sheet">
          <div class="segmented"><button v-for="f in ['全部','运输中','已结束','待准备']" :key="f"
              :class="{selected:f===historyFilter}" @click="historyFilter=f">{{f}}</button></div><button
            v-for="t in p.tasks.filter(t=>historyFilter==='全部'||stateNames[t.state]===historyFilter)" :key="t.id"
            class="list-row record-row" @click="t.state==='ready'?p.prepare(t.boxId):p.openTask(t.id)">
            <Icon :name="t.state==='completed'?'archive':'task'" />
            <span><strong>{{t.item||'待绑定物品'}}</strong><small>{{t.id}} ·
                {{stateNames[t.state]}}</small><small>{{t.boxId}} ·
                {{t.start||'尚未开始'}}{{t.end?' — '+t.end:''}}</small></span>
            <Icon name="chevron" :size="18" />
          </button>
        </section>
        <section v-else-if="p.page==='environment'" class="sheet history-environment">
          <div class="section-heading">
            <div>
              <h2>{{p.task.item}} · 环境记录</h2>
              <p>{{p.task.id}}</p>
            </div><span>{{p.task.state==='completed'?'已结束 · 历史':'本次运输'}}</span>
          </div>
          <p>{{demoDate}} {{p.task.start||'尚未开始'}} — {{p.task.end||'14:32'}}</p>
          <Environment :temperature="p.task.temperature" :humidity="p.task.humidity" :scenario="p.taskScenario"
            :historical="p.task.state==='completed'" :sample-time="p.task.sampleTime||p.task.end||'14:32'" />
          <h3>温度变化</h3>
          <Trend :temperature="p.task.temperature" :start="p.task.start||'14:32'" :end="p.task.end||'14:32'"
            :alarm="p.taskScenario==='alarm'" :gap="['invalid','offline','stale'].includes(p.taskScenario)" />
          <p class="data-note">曲线使用公开模拟样本；缺口不连接。只包含本次运输，不能据末次读数推断全程安全。</p>
        </section>
        <section v-else-if="p.page==='alerts'" class="sheet alerts-page">
          <h2>本次运输异常记录</h2>
          <p>{{p.task.id}} · {{p.task.item}}</p><button v-if="p.taskScenario==='alarm'" class="list-row alert-record"
            @click="dialog='alert'">
            <Icon name="alert" /><span><strong>温度超出演示范围</strong><small>{{p.alarmTime}}
                · 8.6 °C · 样本有效</small></span>
            <Icon name="chevron" :size="18" />
          </button>
          <div v-else class="empty-record">
            <Icon name="shield" :size="30" />
            <h3>{{['invalid','unknown','offline','stale'].includes(p.taskScenario)?'当前环境判断不可用':'暂无本次演示异常记录'}}</h3>
            <p>没有异常记录不代表整次运输安全；请结合环境数据与缺口查看。</p>
          </div>
          <p class="data-note">此页只读。人工确认、处理和关闭为 Planned。</p><button class="text-button"
            @click="p.go('environment')">查看环境记录</button>
        </section>
        <section v-else-if="p.page==='account'&&p.role==='rider'" class="sheet account-page">
          <div class="section-heading">
            <h2>林沐</h2><span class="avatar">林</span>
          </div>
          <p>骑手 / 个人箱体拥有者 · 示例身份</p>
          <h3>使用关系</h3>
          <p>个人拥有 1 个箱体；企业分配 1 个箱体。企业资产所有权仍归企业。</p>
          <details open>
            <summary>我的操作权限</summary>
            <ul>
              <li>查看获准箱体和本人任务。</li>
              <li>物品绑定、开始与结束运输仅更新本地原型。</li>
              <li>箱体所有权、使用资格、设备控制资格分别核验。</li>
              <li>画像、校准、灯风扇与布撤防未接入本原型，Planned。</li>
            </ul>
          </details><button class="button secondary" @click="exit">退出演示工作台</button>
        </section>
      </template>
    </main>
    <footer class="product-footer">SmartBox · 独立交互原型，真实鉴权与服务接入为 Planned。</footer>
    <nav v-if="p.role==='rider'&&p.entered&&!['entry','expired'].includes(p.page)" class="mobile-nav" aria-label="移动导航">
      <button v-for="n in nav" :key="n.id" :class="{current:p.page===n.id}" @click="p.go(n.id)">
        <Icon :name="n.icon" :size="21" /><span>{{n.label}}</span>
      </button></nav>
    <div v-if="dialog" class="modal-shade" @click.self="dialog=null">
      <section ref="dialogElement" class="modal" role="dialog" aria-modal="true"
        :aria-label="dialog==='alert'?'异常详情':dialog==='start'?'确认开始运输':'确认结束运输'"><button class="modal-close"
          aria-label="关闭弹窗" @click="dialog=null">
          <Icon name="close" />
        </button>
        <h2>{{dialog==='alert'?'温度异常详情':dialog==='start'?'确认开始运输？':'确认结束运输？'}}</h2>
        <p>
          {{dialog==='alert'?'有效样本 8.6 °C，超出演示画像参考 2–8 °C。此为统一 Mock 告警。':dialog==='start'?'请核对箱体与物品。确认后开始记录本次演示运输。':'结束后保留本次记录，不再使用该箱体后续数据更新此任务。'}}
        </p>
        <dl class="definition-list">
          <div>
            <dt>物品</dt>
            <dd>{{p.task.item}}</dd>
          </div>
          <div>
            <dt>运输编号</dt>
            <dd>{{p.task.id}}</dd>
          </div>
          <div v-if="p.role==='rider'">
            <dt>箱体</dt>
            <dd>{{p.task.boxId}}</dd>
          </div>
        </dl>
        <p v-if="p.error" class="form-error" role="alert">{{p.error}}</p>
        <p class="data-note">{{dialog==='alert'?'仅查看，不提供虚假处理操作。':'开始/结束条件、离线策略与物品数量仍待确认。'}}</p>
        <div class="form-actions"><button class="button secondary"
            @click="dialog=null">{{dialog==='alert'?'关闭':'返回核对'}}</button><button v-if="dialog!=='alert'"
            :class="['button',dialog==='finish'?'danger':'primary']"
            @click="confirm">{{dialog==='start'?'开始运输':'结束运输'}}</button></div>
      </section>
    </div>
    <div v-if="p.notice" class="toast" role="status">{{p.notice}}<button aria-label="关闭提示"
        @click="p.notice=''">×</button></div>
  </div>
</template>
