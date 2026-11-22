import { computed, nextTick, ref, type UnwrapNestedRefs } from 'vue';
import { boxes, initialTasks, demoCode, currentTime, scenarioOptions, type Task, type Page, type Role, type Scenario } from './model';

export function usePreview() {
  const q = new URLSearchParams(location.search);
  const role = ref<Role>(q.get('role') === 'customer' ? 'customer' : 'rider');
  const scenario = ref<Scenario>(scenarioOptions.find(s => s.id === q.get('scenario'))?.id || 'normal');
  const tasks = ref(initialTasks());
  const selectedTaskId = ref(tasks.value.some(t => t.id === q.get('task')) ? q.get('task')! : tasks.value[0].id);
  const selectedBoxId = ref(tasks.value.find(t => t.id === selectedTaskId.value)!.boxId);
  const entered = ref(role.value === 'rider');
  const allowedPages: Page[] = ['entry','home','boxes','box','prepare','task','history','environment','alerts','account','expired'];
  const requestedPage = allowedPages.includes(q.get('page') as Page) ? q.get('page') as Page : 'home';
  const page = ref<Page>(role.value === 'customer' ? 'entry' : requestedPage);
  const transportNo = ref(q.get('task') || 'DEMO-1123-018');
  const verificationCode = ref(demoCode);
  const error = ref('');
  const notice = ref('');
  const task = computed(() => tasks.value.find(t => t.id === selectedTaskId.value)!);
  const box = computed(() => boxes.find(b => b.id === selectedBoxId.value)!);
  const activeTasks = computed(() => scenario.value === 'empty' ? [] : tasks.value.filter(t => t.state === 'active'));
  const taskScenario = computed<Scenario>(() => task.value.state === 'completed' ? task.value.quality || 'normal' : scenario.value);
  const alarmTime = computed(() => task.value.alarmTime || (task.value.start && task.value.start > '14:26' ? task.value.start : '14:26'));
  const denied = (id: string) => scenario.value === 'empty' || (scenario.value === 'revoked' && boxes.find(b => b.id === id)?.source === 'assigned');
  const blocked = (id: string) => denied(id) ? '使用授权已失效' : tasks.value.some(t => t.boxId === id && t.state === 'active') ? '正在运输' : scenario.value === 'offline' ? '设备离线，开始策略待确认' : '';
  function go(next: Page) {
    page.value = role.value === 'customer' && !entered.value && next !== 'expired' ? 'entry' : next;
    if (role.value === 'customer' && !['entry','home','task','environment','alerts','expired'].includes(page.value)) page.value = 'home';
    error.value = ''; void nextTick(() => window.scrollTo(0,0));
  }
  function openTask(id: string, next: Page = 'task') {
    const found = tasks.value.find(t => t.id === id);
    if (!found || (role.value === 'customer' && id !== selectedTaskId.value)) return;
    selectedTaskId.value = found.id; selectedBoxId.value = found.boxId; go(next);
  }
  function openBox(id: string) {
    selectedBoxId.value = id;
    const linked = tasks.value.find(t => t.boxId === id && t.state === 'active');
    if (linked) selectedTaskId.value = linked.id;
    go('box');
  }
  function prepare(id?: string) {
    if (scenario.value === 'empty') { notice.value = '当前没有可使用的箱体'; return; }
    selectedBoxId.value = id || boxes.find(b => !blocked(b.id))?.id || boxes[0].id;
    go('prepare');
  }
  function chooseBox(id: string) {
    if (blocked(id)) return false;
    selectedBoxId.value = id;
    let draft = tasks.value.find(t => t.boxId === id && t.state === 'ready');
    if (!draft) {
      draft = {id:`DEMO-1123-N${tasks.value.length + 1}`,boxId:id,item:'',description:'演示物品',state:'ready',start:null,end:null,temperature:box.value.temp,humidity:box.value.humidity};
      tasks.value.push(draft);
    }
    selectedTaskId.value = draft.id; return true;
  }
  function bind(name: string) {
    if (denied(task.value.boxId) || task.value.state !== 'ready' || task.value.boxId !== selectedBoxId.value) { error.value = '当前任务不可绑定，请重新选择箱体'; return false; }
    if (!name.trim()) { error.value = '请填写物品名称'; return false; }
    task.value.item = name.trim(); error.value = ''; return true;
  }
  function start() {
    const t = task.value;
    if (blocked(t.boxId) || !t.item || t.state !== 'ready' || t.boxId !== selectedBoxId.value) { error.value = '无法开始：请核对使用资格、物品和箱体占用'; return false; }
    t.state = 'active'; t.start = currentTime; go('task'); notice.value = '运输已开始（演示）'; return true;
  }
  function finish() {
    if (denied(task.value.boxId) || task.value.state !== 'active') { error.value = '该任务当前不可结束'; return false; }
    task.value.state = 'completed'; task.value.end = currentTime;
    task.value.sampleTime = ['offline','stale'].includes(scenario.value) ? '14:20' : currentTime;
    if (scenario.value === 'alarm') { task.value.temperature = 8.6; task.value.quality = 'alarm'; task.value.alarmTime = alarmTime.value; }
    else task.value.quality = scenario.value;
    notice.value = '运输已结束，本次记录已保留（演示）'; return true;
  }
  function verify() {
    entered.value = false;
    const target = tasks.value.find(t => t.id === transportNo.value.trim() && t.state !== 'ready');
    if (!target || verificationCode.value !== demoCode || scenario.value === 'verify-error') { error.value = '运输编号或验证码不匹配，请核对后重试。'; return; }
    if (scenario.value === 'expired') { go('expired'); return; }
    selectedTaskId.value = target.id; selectedBoxId.value = target.boxId; entered.value = true; go('home');
  }
  function reset() {
    tasks.value = initialTasks(); selectedTaskId.value = tasks.value[0].id; selectedBoxId.value = boxes[0].id;
    entered.value = role.value === 'rider'; page.value = entered.value ? 'home' : 'entry'; error.value = ''; notice.value = '';
    transportNo.value = 'DEMO-1123-018'; verificationCode.value = demoCode;
  }
  function applyScenario() {
    reset();
    if (scenario.value === 'idle') tasks.value[0].state = 'completed', tasks.value[0].end = '14:20';
    if (scenario.value === 'multiple') { tasks.value[1].state = 'active'; tasks.value[1].item = '鲜蔬补给包'; tasks.value[1].start = '14:00'; }
    if (scenario.value === 'expired') { entered.value = false; page.value = 'expired'; }
    if (scenario.value === 'verify-error') { entered.value = false; page.value = 'entry'; verificationCode.value = 'WRONG-DEMO'; }
  }
  applyScenario();
  // URL task is a form convenience only; it never establishes guest access.
  if (q.get('task') && tasks.value.some(t => t.id === q.get('task'))) {
    selectedTaskId.value = q.get('task')!; selectedBoxId.value = task.value.boxId;
    transportNo.value = q.get('task')!;
  }
  if (role.value === 'rider' && scenario.value !== 'expired') page.value = requestedPage;
  return {role,scenario,tasks,selectedTaskId,selectedBoxId,entered,page,transportNo,verificationCode,error,notice,task,box,activeTasks,taskScenario,alarmTime,denied,blocked,go,openTask,openBox,prepare,chooseBox,bind,start,finish,verify,reset,applyScenario};
}
export type Preview = ReturnType<typeof usePreview>;
export type PreviewState = UnwrapNestedRefs<Preview>;
